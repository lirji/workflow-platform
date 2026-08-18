package com.lrj.workflow.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

/**
 * core 通用配置。提供 {@link TransactionTemplate}(发起流程需程序化事务以处理并发幂等的回滚-重读)。
 */
@Configuration
public class WorkflowCoreConfig {

    @Bean
    public TransactionTemplate workflowTransactionTemplate(PlatformTransactionManager txManager) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        // 发起流程必须独立提交：Kafka listener 的外层事务负责 inbox；崩溃重投再命中发起幂等键。
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
