package com.lrj.workflow.server;

import com.lrj.workflow.server.admin.DefinitionAdminService;
import com.lrj.workflow.server.admin.ProcessDefinitionView;
import com.lrj.workflow.server.web.AdminDefinitionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDefinitionControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DefinitionAdminService svc;
    @MockBean com.lrj.workflow.server.security.WorkflowIdentityResolver identity;

    @BeforeEach
    void stubTenant() {
        when(identity.tenant(anyString())).thenAnswer(i -> i.getArgument(0));
        when(identity.actor(any())).thenReturn(new com.lrj.workflow.protocol.event.Actor("sub-1", "u", "d"));
    }

    @Test
    void listDefinitions() throws Exception {
        when(svc.list("his")).thenReturn(List.of(
                new ProcessDefinitionView("hisRxReview:1:9", "hisRxReview", "审方", 1, "his", false, "dep1")));
        mvc.perform(get("/api/v1/admin/definitions").header("X-Workflow-Tenant", "his"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("hisRxReview"))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void deployReturnsView() throws Exception {
        when(svc.deploy(eq("his"), eq("demo"), anyString(), eq("sub-1"))).thenReturn(
                new ProcessDefinitionView("demo:1:10", "demo", "demo", 1, "his", false, "dep2"));
        mvc.perform(post("/api/v1/admin/definitions/deploy")
                        .header("X-Workflow-Tenant", "his")
                        .contentType("application/json")
                        .content("{\"name\":\"demo\",\"bpmnXml\":\"<definitions/>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("demo"));
    }

    @Test
    void suspendReturns204() throws Exception {
        mvc.perform(post("/api/v1/admin/definitions/demo:1:10/suspend"))
                .andExpect(status().isNoContent());
        verify(svc).suspendDefinition("demo:1:10");
    }
}
