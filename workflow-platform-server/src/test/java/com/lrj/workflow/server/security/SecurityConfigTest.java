package com.lrj.workflow.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void groupNormalizationDoesNotEscalateSuffixAdmin() {
        assertThat(SecurityConfig.normalizeGroup("ADMIN")).isEqualTo("ADMIN");
        assertThat(SecurityConfig.normalizeGroup("org/PHARMACIST")).isEqualTo("PHARMACIST");
        assertThat(SecurityConfig.normalizeGroup("his_ADMIN")).isEqualTo("HIS_ADMIN");
    }

    @Test
    void validatorRequiresConfiguredIssuerAndAudience() {
        WorkflowSecurityProperties props = new WorkflowSecurityProperties();
        props.setIssuerUri("https://id.example.com");
        props.setAudience("workflow-platform");
        SecurityConfig config = new SecurityConfig(props);
        Instant now = Instant.now();

        Jwt valid = Jwt.withTokenValue("ok").header("alg", "none")
                .issuer("https://id.example.com").audience(List.of("workflow-platform"))
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60)).build();
        Jwt wrongAudience = Jwt.withTokenValue("bad-aud").header("alg", "none")
                .issuer("https://id.example.com").audience(List.of("another-app"))
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60)).build();
        Jwt wrongIssuer = Jwt.withTokenValue("bad-iss").header("alg", "none")
                .issuer("https://evil.example.com").audience(List.of("workflow-platform"))
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(60)).build();

        assertThat(config.jwtValidator().validate(valid).hasErrors()).isFalse();
        assertThat(config.jwtValidator().validate(wrongAudience).hasErrors()).isTrue();
        assertThat(config.jwtValidator().validate(wrongIssuer).hasErrors()).isTrue();
    }
}
