package com.lrj.workflow.server.config;

import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flowable 异步执行器线程池调优(外部化,便于按部署压测结果调整)。
 * 多副本安全:Flowable 作业获取用 ACT_RU_JOB 悲观锁(多节点安全),outbox 用 FOR UPDATE SKIP LOCKED + 租约;
 * server 无状态,可水平扩展。见 deploy/README「HA / 水平扩展」。
 */
@Configuration
public class FlowableTuningConfig {

    @Bean
    ProcessEngineConfigurationConfigurer asyncExecutorTuning(
            @Value("${workflow.flowable.async-core-pool:8}") int corePool,
            @Value("${workflow.flowable.async-max-pool:8}") int maxPool,
            @Value("${workflow.flowable.async-queue-size:100}") int queueSize,
            @Value("${workflow.flowable.async-max-jobs-per-acquisition:8}") int maxJobsPerAcquisition) {
        return cfg -> {
            cfg.setAsyncExecutorCorePoolSize(corePool);
            cfg.setAsyncExecutorMaxPoolSize(maxPool);
            cfg.setAsyncExecutorThreadPoolQueueSize(queueSize);
            cfg.setAsyncExecutorMaxAsyncJobsDuePerAcquisition(maxJobsPerAcquisition);
        };
    }
}
