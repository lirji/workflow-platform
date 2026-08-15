package com.lrj.workflow.sdk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SDK 自动配置。{@code workflow.client.enabled=true} 注入 {@link RemoteWorkflowClient},否则 {@link NoopWorkflowClient}。
 */
@Configuration
@EnableConfigurationProperties(WorkflowClientProperties.class)
public class WorkflowSdkAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "workflow.client", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WorkflowClient remoteWorkflowClient(WorkflowClientProperties props) {
        return new RemoteWorkflowClient(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.client", name = "enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean
    public WorkflowClient noopWorkflowClient() {
        return new NoopWorkflowClient();
    }
}
