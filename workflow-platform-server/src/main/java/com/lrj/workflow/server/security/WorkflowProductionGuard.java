package com.lrj.workflow.server.security;

import com.lrj.workflow.server.kafka.KafkaTrustProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * `prod` profile 的启动硬门禁。避免因为默认开发配置或漏配环境变量而以开放模式启动。
 */
@Component
public class WorkflowProductionGuard implements InitializingBean {

    private final Environment env;
    private final WorkflowSecurityProperties security;
    private final KafkaTrustProperties kafkaTrust;

    public WorkflowProductionGuard(Environment env, WorkflowSecurityProperties security,
                                   KafkaTrustProperties kafkaTrust) {
        this.env = env;
        this.security = security;
        this.kafkaTrust = kafkaTrust;
    }

    @Override
    public void afterPropertiesSet() {
        if (!env.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> errors = validateProductionSettings();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("生产配置门禁失败: " + String.join("; ", errors));
        }
    }

    List<String> validateProductionSettings() {
        List<String> errors = new ArrayList<>();
        if (!security.isEnabled()) errors.add("workflow.security.enabled 必须为 true");
        if (!StringUtils.hasText(security.getIssuerUri())) errors.add("workflow.security.issuer-uri 必须配置");
        if (!StringUtils.hasText(security.getAudience())) errors.add("workflow.security.audience 必须配置");
        if (!StringUtils.hasText(security.getTenantClaim())) errors.add("workflow.security.tenant-claim 必须配置");
        if (!kafkaTrust.isEnabled()) errors.add("workflow.kafka-trust.enabled 必须为 true");
        if (!StringUtils.hasText(kafkaTrust.getSourceTenantBindings())) {
            errors.add("workflow.kafka-trust.source-tenant-bindings 必须配置");
        }
        if (!StringUtils.hasText(kafkaTrust.getSourceSigningKeys())) {
            errors.add("workflow.kafka-trust.source-signing-keys 必须配置");
        }
        if (env.getProperty("flowable.database-schema-update", Boolean.class, true)) {
            errors.add("flowable.database-schema-update 必须为 false");
        }
        if (env.getProperty("workflow.pilot.auto-deploy", Boolean.class, true)) {
            errors.add("workflow.pilot.auto-deploy 必须为 false");
        }
        String password = env.getProperty("spring.datasource.password", "");
        if (!StringUtils.hasText(password) || "workflow".equals(password)) {
            errors.add("数据库密码不得为空或使用默认值 workflow");
        }
        return errors;
    }
}
