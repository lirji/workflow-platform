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

/** benefit SKU 上线审批定义、专用动作与 ACK message 的闭环测试。 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "spring.kafka.listener.auto-startup=false",
        "workflow.jobs.enabled=false",
        "workflow.pilot.auto-deploy=false"
})
@Testcontainers(disabledWithoutDocker = true)
class BenefitSkuGoLiveLoopTest {
    private static final String TENANT = "dev-tenant";
    private static final String TEST_DATABASE = "workflow_sku_golive_test";
    private static final String TEST_USER = "workflow_sku_golive_test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(TEST_DATABASE)
            .withUsername(TEST_USER)
            .withPassword("workflow-sku-golive-test-only");

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
        repositoryService.createDeployment().name("benefit-sku-golive-v1")
                .addClasspathResource("bpmn/benefit-sku-golive-v1.bpmn20.xml")
                .tenantId(TENANT).deploy();
    }

    @AfterEach
    void cleanup() {
        requireIsolatedTestDatabase();
        repositoryService.createDeploymentQuery().list()
                .forEach(deployment -> repositoryService.deleteDeployment(deployment.getId(), true));
        jdbc.update("DELETE FROM wf_outbox_event");
        jdbc.update("DELETE FROM wf_inbox_event");
        jdbc.update("DELETE FROM wf_process_link");
    }

    @Test
    void benefitTenantDeploysAndPassAckAdvancesDedicatedMessage() {
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("benefitSkuGoLive")
                .processDefinitionTenantId(TENANT).count()).isEqualTo(1);

        ProcessLink link = processApp.start(TENANT, new StartProcessCommandV1(
                "benefitSkuGoLive", "SKU-9001", "dev-tenant|SKU-9001|0", "operator-1",
                Map.of("skuId", "SKU-9001", "skuVersion", 1)));
        Task task = taskService.createTaskQuery().processInstanceId(link.processInstanceId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("skuGoLiveReview");
        assertThat(taskService.getIdentityLinksForTask(task.getId()))
                .anyMatch(identity -> "BENEFIT_SKU_REVIEWER".equals(identity.getGroupId()));

        String actionId = taskApp.completeReview(task.getId(), TENANT, "PASS", "同意上线",
                new Actor("reviewer-1", "reviewer", "审批人"), TaskAccessContext.disabled());
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM wf_outbox_event WHERE payload::text LIKE ?",
                String.class, "%" + actionId + "%");
        assertThat(payload).contains("SKU_GO_LIVE_APPROVE")
                .contains("skuGoLiveReview")
                .doesNotContain("RX_REVIEW_");
        assertThat(linkRepo.findByInstanceId(link.processInstanceId()).orElseThrow().phase())
                .isEqualTo(ProcessPhase.WAITING_BUSINESS);

        var applied = new WorkflowActionAppliedV1(link.processInstanceId(), task.getId(),
                "benefitSkuGoLive", "SKU-9001", actionId, WorkflowActionStatus.APPLIED,
                2L, null, null);
        assertThat(correlation.correlate(TENANT, applied))
                .isEqualTo(MessageCorrelationService.Outcome.CORRELATED);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(link.processInstanceId()).count()).isZero();
        assertThat(linkRepo.findByInstanceId(link.processInstanceId()).orElseThrow().phase())
                .isEqualTo(ProcessPhase.COMPLETED);
    }

    @Test
    void rejectUsesSkuRejectAction() {
        ProcessLink link = processApp.start(TENANT, new StartProcessCommandV1(
                "benefitSkuGoLive", "SKU-9002", "dev-tenant|SKU-9002|0", "operator-1",
                Map.of("skuId", "SKU-9002", "skuVersion", 1)));
        Task task = taskService.createTaskQuery().processInstanceId(link.processInstanceId()).singleResult();
        String actionId = taskApp.completeReview(task.getId(), TENANT, "REJECT", "资料不完整",
                new Actor("reviewer-1", "reviewer", null), TaskAccessContext.disabled());
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM wf_outbox_event WHERE payload::text LIKE ?",
                String.class, "%" + actionId + "%");
        assertThat(payload).contains("SKU_GO_LIVE_REJECT");
    }

    private void requireIsolatedTestDatabase() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        String user = jdbc.queryForObject("SELECT current_user", String.class);
        if (!TEST_DATABASE.equals(database) || !TEST_USER.equals(user) || !POSTGRES.isRunning()) {
            throw new IllegalStateException("拒绝清理非独占测试数据库: database=" + database + ", user=" + user);
        }
    }
}
