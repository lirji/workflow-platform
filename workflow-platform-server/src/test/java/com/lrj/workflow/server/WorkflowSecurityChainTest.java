package com.lrj.workflow.server;

import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.server.security.SecurityConfig;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import com.lrj.workflow.server.web.TaskController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权链测试:workflow.security.enabled=true 时 API 需有效 JWT;且 actor 由 JWT 派生覆盖请求体(防伪造)。
 * 用 spring-security-test 的 jwt() 注入认证,JwtDecoder mock 掉(无网络)。
 */
@WebMvcTest(TaskController.class)
@Import({SecurityConfig.class, WorkflowIdentityResolver.class})
@TestPropertySource(properties = {
        "workflow.security.enabled=true",
        "workflow.security.jwk-set-uri=http://localhost/jwks"
})
class WorkflowSecurityChainTest {

    @Autowired MockMvc mvc;
    @MockBean TaskApplicationService taskApp;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void noToken_is401() throws Exception {
        mvc.perform(get("/api/v1/tasks")
                        .header("X-Workflow-Tenant", "his")
                        .param("definitionKey", "hisRxReview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withJwt_is200() throws Exception {
        when(taskApp.findTasks(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/tasks")
                        .with(jwt().authorities(new SimpleGrantedAuthority("PHARMACIST")))
                        .header("X-Workflow-Tenant", "his")
                        .param("definitionKey", "hisRxReview"))
                .andExpect(status().isOk());
    }

    /** 办理时 actor 由 JWT(sub/username)派生,覆盖请求体里伪造的 actorSub —— 防身份伪造。 */
    @Test
    void completeReview_actorDerivedFromJwt_overridesBody() throws Exception {
        ArgumentCaptor<Actor> actorCap = ArgumentCaptor.forClass(Actor.class);
        when(taskApp.completeReview(eq("t1"), eq("his"), eq("PASS"), any(), actorCap.capture())).thenReturn("act-1");

        mvc.perform(post("/api/v1/tasks/t1/complete-review")
                        .with(jwt().jwt(j -> j.subject("sub-xyz").claim("preferred_username", "pharma01").claim("name", "药师张三")))
                        .header("X-Workflow-Tenant", "his")
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\",\"opinion\":\"同意\",\"actorSub\":\"SPOOFED\"}"))
                .andExpect(status().isAccepted());

        assertThat(actorCap.getValue().subjectId()).isEqualTo("sub-xyz");
        assertThat(actorCap.getValue().username()).isEqualTo("pharma01");
    }
}
