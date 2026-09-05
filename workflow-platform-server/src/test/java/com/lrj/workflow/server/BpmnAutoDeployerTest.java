package com.lrj.workflow.server;

import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证试点自动部署不会只部署 HIS，而遗漏真正承载权益待办的 tenant。 */
@SpringBootTest(properties = {
        "workflow.pilot.auto-deploy=true",
        "workflow.pilot.benefit-tenant=benefit-test"
})
class BpmnAutoDeployerTest {
    @Autowired RepositoryService repositoryService;

    @AfterEach
    void cleanup() {
        repositoryService.createDeploymentQuery().list()
                .forEach(deployment -> repositoryService.deleteDeployment(deployment.getId(), true));
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
}
