package com.lrj.workflow.server;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.core.process.ProcessApplicationService;
import com.lrj.workflow.core.task.TaskAccessContext;
import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowActionStatus;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2a 内部闭环集成测试(独占 Testcontainers PG,不经 Kafka/REST):
 * 幂等发起 → 审方 PASS(delegate 写 outbox)→ 模拟 ACK 关联 → 流程结束、link COMPLETED。
 * Kafka 监听器与定时作业关闭,直接调 application services。Docker 不可用时由 Testcontainers 明确跳过。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "spring.kafka.listener.auto-startup=false",
        "workflow.jobs.enabled=false",
        "workflow.pilot.auto-deploy=false"
})
@Testcontainers(disabledWithoutDocker = true)
class RxReviewLoopTest {

    private static final String TEST_DATABASE = "workflow_rx_review_test";
    private static final String TEST_USER = "workflow_rx_review_test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(TEST_DATABASE)
            .withUsername(TEST_USER)
            .withPassword("workflow-rx-review-test-only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired ProcessApplicationService processApp;
    @Autowired TaskApplicationService taskApp;
    @Autowired MessageCorrelationService correlation;
    @Autowired ProcessLinkRepository linkRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void deploy() {
        cleanup();
        repositoryService.createDeployment().name("his-rx-review-v1")
                .addClasspathResource("bpmn/his-rx-review-v1.bpmn20.xml").tenantId("his").deploy();
    }

    @AfterEach
    void cleanup() {
        requireIsolatedTestDatabase();
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
        jdbc.update("DELETE FROM wf_outbox_event");
        jdbc.update("DELETE FROM wf_inbox_event");
        jdbc.update("DELETE FROM wf_process_link");
    }

    /**
     * 广域清理前的不可绕过门禁：即使未来有人误改 DynamicProperty，也不能触碰默认/共享数据库。
     */
    private void requireIsolatedTestDatabase() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        String user = jdbc.queryForObject("SELECT current_user", String.class);
        if (!TEST_DATABASE.equals(database) || !TEST_USER.equals(user) || !POSTGRES.isRunning()) {
            throw new IllegalStateException("拒绝清理非独占测试数据库: database=" + database + ", user=" + user);
        }
    }

    private StartProcessCommandV1 cmd(String enc, String cycle) {
        return new StartProcessCommandV1("hisRxReview", enc, cycle, "test",
                Map.of("encounterId", enc));
    }

    @Test
    void passLoopFromStartToCompleted() {
        ProcessLink link = processApp.start("his", cmd("enc-9001", "cycle-1"));
        assertThat(link.phase()).isEqualTo(ProcessPhase.WAITING_USER);
        String pid = link.processInstanceId();

        // 幂等:同 cmd 再发一次 → 同实例
        ProcessLink again = processApp.start("his", cmd("enc-9001", "cycle-1"));
        assertThat(again.processInstanceId()).isEqualTo(pid);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey("enc-9001").count()).isEqualTo(1);

        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("pharmacistReview");

        String actionId = taskApp.completeReview(task.getId(), "his", "PASS", "同意发药",
                new Actor("sub-1", "pharma01", "药师张三"), TaskAccessContext.disabled());

        // 审方完成 → 泊在 message catch → WAITING_BUSINESS;outbox 有一条 action.requested
        assertThat(linkRepo.findByInstanceId(pid).orElseThrow().phase()).isEqualTo(ProcessPhase.WAITING_BUSINESS);
        Integer outboxCnt = jdbc.queryForObject(
                "SELECT count(*) FROM wf_outbox_event WHERE topic='workflow.action.requested.v1' AND payload::text LIKE ?",
                Integer.class, "%" + actionId + "%");
        assertThat(outboxCnt).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM wf_outbox_event WHERE payload::text LIKE ?", String.class, "%" + actionId + "%");
        assertThat(payload).contains("RX_REVIEW_PASS").contains("enc-9001");

        // 模拟业务落地 ACK 关联回流程
        var applied = new WorkflowActionAppliedV1(pid, task.getId(), "hisRxReview", "enc-9001", actionId,
                WorkflowActionStatus.APPLIED, 1L, null, null);
        assertThat(correlation.correlate("other", applied)).isEqualTo(MessageCorrelationService.Outcome.ACTION_MISMATCH);
        assertThat(correlation.correlate("his", applied)).isEqualTo(MessageCorrelationService.Outcome.CORRELATED);

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count()).isZero();
        ProcessLink completed = linkRepo.findByInstanceId(pid).orElseThrow();
        assertThat(completed.phase()).isEqualTo(ProcessPhase.COMPLETED);
        assertThat(completed.status()).isEqualTo("ENDED");
    }

    @Test
    void ackWithWrongActionIdIsRejected() {
        ProcessLink link = processApp.start("his", cmd("enc-9002", "cycle-1"));
        String pid = link.processInstanceId();
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskApp.completeReview(task.getId(), "his", "PASS", null,
                new Actor("sub-1", "pharma01", null), TaskAccessContext.disabled());

        var wrong = new WorkflowActionAppliedV1(pid, task.getId(), "hisRxReview", "enc-9002", "not-the-action",
                WorkflowActionStatus.APPLIED, 1L, null, null);
        assertThat(correlation.correlate("his", wrong)).isEqualTo(MessageCorrelationService.Outcome.ACTION_MISMATCH);
        // 流程仍泊着,未被错误 ACK 推进
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count()).isEqualTo(1);
    }
}
