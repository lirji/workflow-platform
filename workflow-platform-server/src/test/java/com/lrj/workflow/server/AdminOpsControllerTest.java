package com.lrj.workflow.server;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
import com.lrj.workflow.server.admin.AdminOpsService;
import com.lrj.workflow.server.admin.DeadLetterJobView;
import com.lrj.workflow.server.web.AdminOpsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AdminOpsController Web 层测试(mock service,关安全过滤器专测映射)。 */
@WebMvcTest(AdminOpsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminOpsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ProcessQueryService query;
    @MockBean AdminOpsService ops;
    @MockBean com.lrj.workflow.server.security.WorkflowIdentityResolver identity;

    @BeforeEach
    void stubTenantPassthrough() {
        when(identity.tenant(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void listInstancesByPhase() throws Exception {
        when(query.search("his", null, "INCIDENT", 100)).thenReturn(List.of(
                new ProcessInstanceView("pi1", "his", "hisRxReview", "90003", "cycle-1", "INCIDENT", "ERROR", true, false)));
        mvc.perform(get("/api/v1/admin/incidents").header("X-Workflow-Tenant", "his"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].processInstanceId").value("pi1"))
                .andExpect(jsonPath("$[0].phase").value("INCIDENT"));
    }

    @Test
    void suspendReturns204AndDelegates() throws Exception {
        mvc.perform(post("/api/v1/admin/instances/pi1/suspend"))
                .andExpect(status().isNoContent());
        verify(ops).suspend("pi1");
    }

    @Test
    void terminateReturns204WithReason() throws Exception {
        mvc.perform(post("/api/v1/admin/instances/pi1/terminate").param("reason", "误发起"))
                .andExpect(status().isNoContent());
        verify(ops).terminate("pi1", "误发起");
    }

    @Test
    void deadLetterJobsListed() throws Exception {
        when(ops.deadLetterJobs(100)).thenReturn(List.of(
                new DeadLetterJobView("j1", "pi1", "serviceTask1", 0, "boom")));
        mvc.perform(get("/api/v1/admin/jobs/dead-letter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("j1"))
                .andExpect(jsonPath("$[0].exceptionMessage").value("boom"));
    }

    @Test
    void retryJobReturns204WithDefaultRetries() throws Exception {
        mvc.perform(post("/api/v1/admin/jobs/j1/retry"))
                .andExpect(status().isNoContent());
        verify(ops).retryJob(eq("j1"), anyInt());
    }
}
