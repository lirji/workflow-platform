package com.lrj.workflow.server;

import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import com.lrj.workflow.server.security.WorkflowSecurityProperties;
import com.lrj.workflow.server.web.TaskController;
import com.lrj.workflow.server.web.WorkflowExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TaskController Web 层测试(mock service):查待办映射 + 办理返回 202/PENDING_BUSINESS。
 * 关闭安全过滤器:本切片只测 Controller 逻辑,鉴权链由 WorkflowSecurityChainTest 覆盖。 */
@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WorkflowExceptionHandler.class, WorkflowIdentityResolver.class})
@EnableConfigurationProperties(WorkflowSecurityProperties.class)
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TaskApplicationService taskApp;
    @MockBean com.lrj.workflow.server.metrics.WorkflowMetrics metrics;
    @MockBean com.lrj.workflow.server.audit.WorkflowAudit audit;

    @Test
    void listTasksByBusinessKey() throws Exception {
        when(taskApp.findTasks("his", "hisRxReview", "enc-1")).thenReturn(List.of(
                new TaskView("t1", "pharmacistReview", "药师审方", "pi1", "hisRxReview", "enc-1", "his",
                        null, List.of("PHARMACIST"), 1L)));
        mvc.perform(get("/api/v1/tasks")
                        .header("X-Workflow-Tenant", "his")
                        .param("definitionKey", "hisRxReview")
                        .param("businessKey", "enc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("t1"))
                .andExpect(jsonPath("$[0].candidateGroups[0]").value("PHARMACIST"));
    }

    @Test
    void completeReviewReturns202Accepted() throws Exception {
        when(taskApp.completeReview(ArgumentMatchers.eq("t1"), ArgumentMatchers.eq("his"),
                ArgumentMatchers.eq("PASS"), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("act-123");
        mvc.perform(post("/api/v1/tasks/t1/complete-review")
                        .header("X-Workflow-Tenant", "his")
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\",\"opinion\":\"同意\",\"actorSub\":\"sub1\",\"actorUsername\":\"p01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value("act-123"))
                .andExpect(jsonPath("$.status").value("PENDING_BUSINESS"));
    }

    @Test
    void claimDelegatesToService() throws Exception {
        mvc.perform(post("/api/v1/tasks/t1/claim")
                        .header("X-Workflow-Tenant", "his")
                        .param("userId", "p01"))
                .andExpect(status().isNoContent());
        verify(taskApp).claimTask("his", "t1", "p01");
    }

    @Test
    void reassignDelegatesToService() throws Exception {
        mvc.perform(post("/api/v1/tasks/t1/reassign")
                        .header("X-Workflow-Tenant", "his")
                        .param("assignee", "p02"))
                .andExpect(status().isNoContent());
        verify(taskApp).reassignTask("his", "t1", "p02");
    }
}
