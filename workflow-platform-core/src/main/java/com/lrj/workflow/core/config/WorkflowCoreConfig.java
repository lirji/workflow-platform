package com.lrj.workflow.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * core 通用配置。提供 {@link TransactionTemplate}(发起流程需程序化事务以处理并发幂等的回滚-重读)。
 */
@Configuration
public class WorkflowCoreConfig {

    @Bean
    public TransactionTemplate workflowTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
