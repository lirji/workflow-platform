package com.lrj.workflow.server;

import com.lrj.workflow.core.config.WorkflowCoreConfig;
import com.lrj.workflow.server.kafka.WorkflowActionAppliedListener;
import com.lrj.workflow.server.kafka.WorkflowStartListener;
import com.lrj.workflow.server.admin.AdminOpsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessagingTransactionContractTest {

    @Test
    void inboxListenersRunInsideDatabaseTransaction() throws Exception {
        assertThat(WorkflowStartListener.class.getMethod("onStart", ConsumerRecord.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(WorkflowActionAppliedListener.class.getMethod("onApplied", ConsumerRecord.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void processStartTemplateUsesRequiresNew() {
        var template = new WorkflowCoreConfig()
                .workflowTransactionTemplate(mock(PlatformTransactionManager.class));
        assertThat(template.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void adminTerminateRunsFlowableDeleteAndLinkUpdateInOneTransaction() throws Exception {
        assertThat(AdminOpsService.class
                .getMethod("terminate", String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
