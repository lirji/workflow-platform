package com.lrj.workflow.server.kafka;

import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 监听统一错误处理:重试 max-attempts 次(固定退避)后,由 DeadLetterPublishingRecoverer
 * 把毒消息投到单一 {@link WorkflowTopics#DLQ},并提交位点 —— 避免无限重投阻塞分区。
 * 原始 topic/异常信息由 recoverer 写入 DLT header,消费方据此落库/重放。
 * Spring Boot 自动把该 CommonErrorHandler 装到监听容器工厂。
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> template,
            @Value("${workflow.dlq.max-attempts:3}") int maxAttempts,
            @Value("${workflow.dlq.backoff-ms:1000}") long backoffMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(WorkflowTopics.DLQ, -1));
        // maxAttempts 次总尝试 = 初次 + (maxAttempts-1) 次重试。
        FixedBackOff backOff = new FixedBackOff(backoffMs, Math.max(0, maxAttempts - 1L));
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /**
     * DLQ 是错误链终点，不能再套用“失败后投回 workflow.dlq.v1”的全局 handler。
     * 落库失败时停止该容器并保留未提交位点，数据库恢复后由运维重启服务继续消费。
     */
    @Bean("workflowDlqKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> workflowDlqKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
