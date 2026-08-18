package com.lrj.workflow.server;

import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.server.admin.AdminOpsService;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantIsolationServiceTest {

    @Test
    void instanceReadAndTimelineUseTenantScopedLink() {
        ProcessLinkRepository links = mock(ProcessLinkRepository.class);
        when(links.findByTenantAndInstanceId("other", "pi-1")).thenReturn(Optional.empty());
        HistoryService history = mock(HistoryService.class);
        ProcessQueryService query = new ProcessQueryService(links, history, mock(RuntimeService.class));

        assertThat(query.getInstance("other", "pi-1")).isEmpty();
        assertThatThrownBy(() -> query.timeline("other", "pi-1"))
                .isInstanceOf(FlowableObjectNotFoundException.class);
        verify(history, never()).createHistoricActivityInstanceQuery();
    }

    @Test
    void adminMutationStopsBeforeFlowableWhenTenantDoesNotOwnInstance() {
        ProcessLinkRepository links = mock(ProcessLinkRepository.class);
        when(links.findByTenantAndInstanceId("other", "pi-1")).thenReturn(Optional.empty());
        RuntimeService runtime = mock(RuntimeService.class);
        AdminOpsService ops = new AdminOpsService(runtime, mock(ManagementService.class), links,
                mock(WorkflowMetrics.class), mock(WorkflowAudit.class));

        assertThatThrownBy(() -> ops.suspend("other", "pi-1"))
                .isInstanceOf(FlowableObjectNotFoundException.class);
        verify(runtime, never()).suspendProcessInstanceById("pi-1");
    }

    @Test
    void endedInstanceDefinitionIdComesFromTenantScopedHistory() {
        ProcessLinkRepository links = mock(ProcessLinkRepository.class);
        when(links.findByTenantAndInstanceId("his", "pi-1")).thenReturn(Optional.of(
                new ProcessLink(1, "his", "hisRxReview", "enc-1", "cycle-1", "pi-1",
                        ProcessPhase.COMPLETED, "ENDED", 1)));
        RuntimeService runtime = mock(RuntimeService.class);
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtime.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pi-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.singleResult()).thenReturn(null);
        HistoryService history = mock(HistoryService.class);
        HistoricProcessInstanceQuery historyQuery = mock(HistoricProcessInstanceQuery.class);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(history.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
        when(historyQuery.processInstanceId("pi-1")).thenReturn(historyQuery);
        when(historyQuery.singleResult()).thenReturn(historic);
        when(historic.getProcessDefinitionId()).thenReturn("hisRxReview:1:old");

        ProcessQueryService query = new ProcessQueryService(links, history, runtime);

        assertThat(query.processDefinitionId("his", "pi-1", "hisRxReview"))
                .contains("hisRxReview:1:old");
        assertThat(query.processDefinitionId("his", "pi-1", "anotherDefinition")).isEmpty();
    }
}
