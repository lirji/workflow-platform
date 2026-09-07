package com.lrj.workflow.server.security;

import com.lrj.workflow.core.WorkflowAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowIdentityResolverTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtTenantIsAuthoritativeAndHeaderMismatchIsRejected() {
        WorkflowSecurityProperties props = securedProperties();
        authenticate(jwtWithTenant("his"), "PHARMACIST");
        WorkflowIdentityResolver resolver = new WorkflowIdentityResolver(props);

        assertThat(resolver.tenant(null)).isEqualTo("his");
        assertThat(resolver.tenant("his")).isEqualTo("his");
        assertThatThrownBy(() -> resolver.tenant("other"))
                .isInstanceOf(WorkflowAccessDeniedException.class);
        assertThat(resolver.taskAccess().principalId()).isEqualTo("pharma01");
        assertThat(resolver.taskAccess().authorities()).containsExactly("PHARMACIST");
    }

    @Test
    void missingTenantClaimFailsClosed() {
        WorkflowSecurityProperties props = securedProperties();
        authenticate(jwtWithTenant(null), "PHARMACIST");

        assertThatThrownBy(() -> new WorkflowIdentityResolver(props).tenant("his"))
                .isInstanceOf(WorkflowAccessDeniedException.class)
                .hasMessageContaining("缺少租户");
    }

    @Test
    void casdoorPropertiesTenantIsAuthoritativeAndOwnerIsIgnored() {
        WorkflowSecurityProperties props = securedProperties();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject("sub-1")
                .claim("owner", "workflow")
                .claim("properties", Map.of("tenant_id", "benefit-center"))
                .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(60))
                .build();
        authenticate(jwt, "ADMIN");

        WorkflowIdentityResolver resolver = new WorkflowIdentityResolver(props);
        assertThat(resolver.tenant("benefit-center")).isEqualTo("benefit-center");
        assertThatThrownBy(() -> resolver.tenant("workflow"))
                .isInstanceOf(WorkflowAccessDeniedException.class)
                .hasMessageContaining("不一致");
    }

    private static WorkflowSecurityProperties securedProperties() {
        WorkflowSecurityProperties props = new WorkflowSecurityProperties();
        props.setEnabled(true);
        props.setTenantClaim("tenant_id");
        return props;
    }

    private static Jwt jwtWithTenant(String tenant) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .subject("sub-1").claim("preferred_username", "pharma01")
                .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) builder.claim("tenant_id", tenant);
        return builder.build();
    }

    private static void authenticate(Jwt jwt, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
    }
}
