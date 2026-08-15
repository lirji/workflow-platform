package com.lrj.workflow.server;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1:审方 BPMN(his-rx-review-v1)结构校验 —— 可部署,且关键节点/候选组/消息 catch 符合 §7.1 设计。
 * serviceTask 的 delegateExpression 在执行期才解析,部署期不需要 bean 存在。
 */
@SpringBootTest
class RxReviewBpmnModelTest {

    @Autowired RepositoryService repositoryService;

    @AfterEach
    void cleanup() {
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
    }

    @Test
    void rxReviewBpmnDeploysWithExpectedStructure() {
        repositoryService.createDeployment()
                .name("his-rx-review-v1")
                .addClasspathResource("bpmn/his-rx-review-v1.bpmn20.xml")
                .tenantId("his")
                .deploy();

        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("hisRxReview").processDefinitionTenantId("his").singleResult();
        assertThat(def).isNotNull();

        BpmnModel model = repositoryService.getBpmnModel(def.getId());
        var process = model.getMainProcess();

        assertThat(process.getFlowElement("pharmacistReview")).isInstanceOf(UserTask.class);
        UserTask review = (UserTask) process.getFlowElement("pharmacistReview");
        assertThat(review.getCandidateGroups()).contains("PHARMACIST");

        assertThat(process.getFlowElement("prepareAction")).isInstanceOf(ServiceTask.class);
        ServiceTask prepare = (ServiceTask) process.getFlowElement("prepareAction");
        assertThat(prepare.getImplementation()).contains("rxReviewActionOutboxDelegate");

        assertThat(process.getFlowElement("waitApplied")).isInstanceOf(IntermediateCatchEvent.class);
        assertThat(process.getFlowElement("manualRepair")).isInstanceOf(UserTask.class);
        UserTask repair = (UserTask) process.getFlowElement("manualRepair");
        assertThat(repair.getCandidateGroups()).contains("ADMIN");
    }
}
