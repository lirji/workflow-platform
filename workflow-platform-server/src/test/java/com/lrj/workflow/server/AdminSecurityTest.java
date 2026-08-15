package com.lrj.workflow.server;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.server.admin.AdminOpsService;
import com.lrj.workflow.server.security.SecurityConfig;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import com.lrj.workflow.server.web.AdminOpsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/v1/admin/** 需 ADMIN 权限:非管理员 403、管理员 200、无 token 401。 */
@WebMvcTest(AdminOpsController.class)
@Import({SecurityConfig.class, WorkflowIdentityResolver.class})
@TestPropertySource(properties = {
        "workflow.security.enabled=true",
        "workflow.security.jwk-set-uri=http://localhost/jwks"
})
class AdminSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean ProcessQueryService query;
    @MockBean AdminOpsService ops;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void nonAdmin_forbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/instances")
                        .with(jwt().authorities(new SimpleGrantedAuthority("PHARMACIST")))
                        .header("X-Workflow-Tenant", "his"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_ok() throws Exception {
        mvc.perform(get("/api/v1/admin/instances")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ADMIN")))
                        .header("X-Workflow-Tenant", "his"))
                .andExpect(status().isOk());
    }

    @Test
    void noToken_401() throws Exception {
        mvc.perform(get("/api/v1/admin/instances").header("X-Workflow-Tenant", "his"))
                .andExpect(status().isUnauthorized());
    }
}
