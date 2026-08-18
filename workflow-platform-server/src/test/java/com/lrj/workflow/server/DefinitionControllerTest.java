package com.lrj.workflow.server;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import com.lrj.workflow.server.web.DefinitionController;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
class DefinitionControllerTest {

    @Autowired MockMvc mvc;
    @MockBean RepositoryService repositoryService;
    @MockBean WorkflowIdentityResolver identity;
    @MockBean ProcessQueryService processQuery;

    @BeforeEach
    void tenant() {
        when(identity.tenant("his")).thenReturn("his");
    }

    @Test
    void instanceTraceLoadsItsExactHistoricDefinition() throws Exception {
        when(processQuery.processDefinitionId("his", "pi-old", "hisRxReview"))
                .thenReturn(Optional.of("hisRxReview:1:old"));
        ProcessDefinitionQuery definitions = mock(ProcessDefinitionQuery.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitions);
        when(definitions.processDefinitionId("hisRxReview:1:old")).thenReturn(definitions);
        when(definitions.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("hisRxReview:1:old");
        when(definition.getKey()).thenReturn("hisRxReview");
        when(definition.getTenantId()).thenReturn("his");
        when(repositoryService.getProcessModel("hisRxReview:1:old"))
                .thenReturn(new ByteArrayInputStream("<definitions id=\"v1\"/>".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/v1/definitions/hisRxReview/xml")
                        .header("X-Workflow-Tenant", "his")
                        .param("processInstanceId", "pi-old"))
                .andExpect(status().isOk())
                .andExpect(content().string("<definitions id=\"v1\"/>"));

        verify(definitions, never()).latestVersion();
    }

    @Test
    void crossTenantOrMismatchedInstanceDoesNotReachDefinitionRepository() throws Exception {
        when(processQuery.processDefinitionId("his", "pi-other", "hisRxReview"))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/definitions/hisRxReview/xml")
                        .header("X-Workflow-Tenant", "his")
                        .param("processInstanceId", "pi-other"))
                .andExpect(status().isNotFound());

        verify(repositoryService, never()).createProcessDefinitionQuery();
    }
}
