package com.lrj.workflow.server.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka 入站的应用层信任边界。绑定格式为逗号分隔的 {@code source=tenant}；同一 source 可配置多条。
 * 该校验是 broker 认证/ACL 之外的第二道防线，不能代替 SASL/TLS。
 */
@Component
@ConfigurationProperties(prefix = "workflow.kafka-trust")
public class KafkaTrustProperties {

    private boolean enabled;
    private String sourceTenantBindings = "";
    private String sourceSigningKeys = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSourceTenantBindings() {
        return sourceTenantBindings;
    }

    public void setSourceTenantBindings(String sourceTenantBindings) {
        this.sourceTenantBindings = sourceTenantBindings;
    }

    public String getSourceSigningKeys() {
        return sourceSigningKeys;
    }

    public void setSourceSigningKeys(String sourceSigningKeys) {
        this.sourceSigningKeys = sourceSigningKeys;
    }
}
