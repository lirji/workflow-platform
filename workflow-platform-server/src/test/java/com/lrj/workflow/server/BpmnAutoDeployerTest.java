package com.lrj.workflow.server;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证试点自动部署不会只部署 HIS，而遗漏真正承载权益待办的 tenant。 */
@SpringBootTest(properties = {
        "workflow.pilot.auto-deploy=true",
        "workflow.pilot.benefit-tenant=benefit-test"
})
class BpmnAutoDeployerTest {
    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired PilotDefinitionProvisioner provisioner;

    @BeforeEach
    void resetDefinitions() {
        repositoryService.createDeploymentQuery().list()
                .forEach(deployment -> repositoryService.deleteDeployment(deployment.getId(), true));
        provisioner.provisionStartupDefinitions("benefit-test");
    }

    @Test
    void deploysSkuGoLiveToConfiguredBenefitTenantAndKeepsHisDeployment() {
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("benefitSkuGoLive")
                .processDefinitionTenantId("benefit-test")
                .count()).isEqualTo(1);
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("benefitSkuGoLive")
                .processDefinitionTenantId("his")
                .count()).isZero();
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("hisRxReview")
                .processDefinitionTenantId("his")
                .count()).isEqualTo(1);
    }

    @Test
    void deploysSkuGoLiveForTrustedInboundTenantWithoutChangingDefaults() {
        provisioner.provisionForTrustedStart("benefit-inbound", "benefitSkuGoLive");
        provisioner.provisionForTrustedStart("benefit-inbound", "benefitSkuGoLive");

        ProcessInstance instance = runtimeService.startProcessInstanceByKeyAndTenantId(
                "benefitSkuGoLive", "SKU-INBOUND",
                Map.of("skuId", "SKU-INBOUND", "skuVersion", 1), "benefit-inbound");

        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("benefitSkuGoLive")
                .processDefinitionTenantId("benefit-inbound")
                .count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getTaskDefinitionKey()).isEqualTo("skuGoLiveReview");
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("hisRxReview")
                .processDefinitionTenantId("his")
                .count()).isEqualTo(1);
    }
}
