package com.lrj.workflow.server;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.core.process.ProcessApplicationService;
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
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2a 内部闭环集成测试(打真 compose PG,不经 Kafka/REST):
 * 幂等发起 → 审方 PASS(delegate 写 outbox)→ 模拟 ACK 关联 → 流程结束、link COMPLETED。
 * Kafka 监听器与定时作业关闭,直接调 application services。PG 不可达则整类跳过(见 [[local-dev-env]])。
 */
@EnabledIf("pgReachable")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:25432/workflow",
        "spring.datasource.username=workflow",
        "spring.datasource.password=workflow",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "spring.kafka.listener.auto-startup=false",
        "workflow.jobs.enabled=false"
})
class RxReviewLoopTest {

    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired ProcessApplicationService processApp;
    @Autowired TaskApplicationService taskApp;
    @Autowired MessageCorrelationService correlation;
    @Autowired ProcessLinkRepository linkRepo;
    @Autowired JdbcTemplate jdbc;

    static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 25432), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void deploy() {
        cleanup();
        repositoryService.createDeployment().name("his-rx-review-v1")
                .addClasspathResource("bpmn/his-rx-review-v1.bpmn20.xml").tenantId("his").deploy();
    }

    @AfterEach
    void cleanup() {
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
        jdbc.update("DELETE FROM wf_outbox_event");
        jdbc.update("DELETE FROM wf_inbox_event");
        jdbc.update("DELETE FROM wf_process_link");
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
                new Actor("sub-1", "pharma01", "药师张三"));

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
        assertThat(correlation.correlate(applied)).isEqualTo(MessageCorrelationService.Outcome.CORRELATED);

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count()).isZero();
        assertThat(linkRepo.findByInstanceId(pid).orElseThrow().phase()).isEqualTo(ProcessPhase.COMPLETED);
    }

    @Test
    void ackWithWrongActionIdIsRejected() {
        ProcessLink link = processApp.start("his", cmd("enc-9002", "cycle-1"));
        String pid = link.processInstanceId();
        Task task = taskService.createTaskQuery().processInstanceId(pid).singleResult();
        taskApp.completeReview(task.getId(), "his", "PASS", null, new Actor("sub-1", "pharma01", null));

        var wrong = new WorkflowActionAppliedV1(pid, task.getId(), "hisRxReview", "enc-9002", "not-the-action",
                WorkflowActionStatus.APPLIED, 1L, null, null);
        assertThat(correlation.correlate(wrong)).isEqualTo(MessageCorrelationService.Outcome.ACTION_MISMATCH);
        // 流程仍泊着,未被错误 ACK 推进
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pid).count()).isEqualTo(1);
    }
}
