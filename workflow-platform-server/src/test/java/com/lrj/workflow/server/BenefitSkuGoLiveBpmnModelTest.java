package com.lrj.workflow.server;

import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.MessageEventDefinition;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 不依赖 Docker 的 SKU 上线 BPMN 部署与关键契约结构测试。 */
@SpringBootTest
class BenefitSkuGoLiveBpmnModelTest {
    @Autowired RepositoryService repositoryService;

    @AfterEach
    void cleanup() {
        repositoryService.createDeploymentQuery().list()
                .forEach(deployment -> repositoryService.deleteDeployment(deployment.getId(), true));
    }

    @Test
    void deploysForBenefitTenantWithDedicatedTaskDelegateAndMessage() {
        repositoryService.createDeployment().name("benefit-sku-golive-v1")
                .addClasspathResource("bpmn/benefit-sku-golive-v1.bpmn20.xml")
                .tenantId("dev-tenant").deploy();

        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("benefitSkuGoLive")
                .processDefinitionTenantId("dev-tenant").singleResult();
        assertThat(definition).isNotNull();
        var process = repositoryService.getBpmnModel(definition.getId()).getMainProcess();
        UserTask review = (UserTask) process.getFlowElement("skuGoLiveReview");
        assertThat(review.getCandidateGroups()).containsExactly("BENEFIT_SKU_REVIEWER");
        ServiceTask delegate = (ServiceTask) process.getFlowElement("prepareSkuAction");
        assertThat(delegate.getImplementation()).contains("skuGoLiveActionOutboxDelegate")
                .doesNotContain("rxReviewActionOutboxDelegate");
        IntermediateCatchEvent wait = (IntermediateCatchEvent) process.getFlowElement("waitApplied");
        assertThat(wait.getEventDefinitions()).hasSize(1);
        MessageEventDefinition message = (MessageEventDefinition) wait.getEventDefinitions().getFirst();
        assertThat(message.getMessageRef()).isEqualTo("benefitSkuGoLiveApplied");
    }
}
