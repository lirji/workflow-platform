package com.lrj.workflow.server.security;

import com.lrj.workflow.server.kafka.KafkaTrustProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowProductionGuardTest {

    @Test
    void prodRejectsDevelopmentDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        WorkflowSecurityProperties props = new WorkflowSecurityProperties();

        assertThatThrownBy(() -> new WorkflowProductionGuard(env, props, new KafkaTrustProperties()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled 必须为 true")
                .hasMessageContaining("audience")
                .hasMessageContaining("kafka-trust")
                .hasMessageContaining("数据库密码");
    }

    @Test
    void completeProductionConfigurationPasses() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("flowable.database-schema-update", "false")
                .withProperty("workflow.pilot.auto-deploy", "false")
                .withProperty("spring.datasource.password", "not-a-default-secret");
        env.setActiveProfiles("prod");
        WorkflowSecurityProperties props = new WorkflowSecurityProperties();
        props.setEnabled(true);
        props.setIssuerUri("https://id.example.com");
        props.setAudience("workflow-platform");
        props.setTenantClaim("tenant_id");
        KafkaTrustProperties kafkaTrust = new KafkaTrustProperties();
        kafkaTrust.setEnabled(true);
        kafkaTrust.setSourceTenantBindings("his-outpatient=his");
        kafkaTrust.setSourceSigningKeys("his-outpatient=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY");

        assertThatCode(() -> new WorkflowProductionGuard(env, props, kafkaTrust).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void nonProductionProfileKeepsLocalCompatibility() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertThatCode(() -> new WorkflowProductionGuard(
                env, new WorkflowSecurityProperties(), new KafkaTrustProperties()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
