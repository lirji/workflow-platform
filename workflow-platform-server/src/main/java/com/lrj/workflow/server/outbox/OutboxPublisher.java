package com.lrj.workflow.server.outbox;

import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事务性发件箱投递:定时领取 READY 事件(SKIP LOCKED)→ 发 Kafka → markSent;失败退避重排。
 * 明确失败采用至少一次重试；模糊 ACK 结果停在 DELIVERY_UNKNOWN，核账后才允许以同 eventId 人工重排。
 * 消费方仍必须按 eventId/actionId 幂等。
 */
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final WorkflowMetrics metrics;

    @Value("${workflow.outbox.batch-size:100}")
    int batchSize;
    @Value("${workflow.outbox.lease-seconds:30}")
    int leaseSeconds;
    @Value("${workflow.outbox.send-timeout-seconds:10}")
    int sendTimeoutSeconds;
    @Value("${spring.kafka.producer.properties.max.block.ms:5000}")
    int producerMaxBlockMs;
    @Value("${spring.kafka.producer.properties.delivery.timeout.ms:8000}")
    int producerDeliveryTimeoutMs;
    @Value("${workflow.outbox.max-attempts:10}")
    int maxAttempts;
    @Value("${workflow.outbox.retry-backoff-seconds:5}")
    int retryBackoffSeconds;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                           WorkflowMetrics metrics) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    @Override
    public void afterPropertiesSet() {
        long totalSendBudgetMs = producerMaxBlockMs + TimeUnit.SECONDS.toMillis(sendTimeoutSeconds);
        if (batchSize < 1 || leaseSeconds < 2 || sendTimeoutSeconds < 1 || producerMaxBlockMs < 1
                || producerDeliveryTimeoutMs < 1
                || producerDeliveryTimeoutMs > TimeUnit.SECONDS.toMillis(sendTimeoutSeconds)
                || totalSendBudgetMs >= TimeUnit.SECONDS.toMillis(leaseSeconds)
                || maxAttempts < 1 || retryBackoffSeconds < 1) {
            throw new IllegalStateException(
                    "outbox 配置非法: 正数配置、producerDeliveryTimeoutMs <= sendTimeoutSeconds，且 "
                            + "producerMaxBlockMs + sendTimeoutSeconds 必须严格小于 leaseSeconds");
        }
    }

    @Scheduled(fixedDelayString = "${workflow.outbox.poll-ms:1000}")
    public void publishBatch() {
        // 每一批使用一次性 fencing token；即使同一 JVM 将来启用并行调度，旧调用也不能提交新租约的结果。
        String leaseOwner = UUID.randomUUID().toString();
        List<OutboxEventRepository.OutboxRow> batch = outbox.claimBatch(batchSize, leaseOwner, leaseSeconds);
        for (var row : batch) {
            // 逐条重置完整租约预算；后排消息若已过期则不再发送，由新 owner 接管。
            if (!outbox.renewLease(row.eventId(), leaseOwner, leaseSeconds)) {
                log.warn("outbox 发送前租约已失效，跳过陈旧批次 eventId={} topic={}", row.eventId(), row.topic());
                continue;
            }
            try {
                kafka.send(row.topic(), row.msgKey(), row.payload()).get(sendTimeoutSeconds, TimeUnit.SECONDS);
                if (!outbox.markSent(row.eventId(), leaseOwner)) {
                    log.warn("outbox 发送成功但租约已失效，忽略陈旧写 eventId={} topic={}", row.eventId(), row.topic());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markDeliveryUnknown(row, leaseOwner, e);
                // 停机/取消时不能继续 send 后续行，否则会把整批都制造成未知结果。
                break;
            } catch (TimeoutException | CancellationException e) {
                markDeliveryUnknown(row, leaseOwner, e);
            } catch (org.apache.kafka.common.errors.InterruptException e) {
                Thread.currentThread().interrupt();
                markDeliveryUnknown(row, leaseOwner, e);
                // KafkaTemplate.send 在 metadata/max.block 阶段也可能同步响应停机中断。
                break;
            } catch (ExecutionException | RuntimeException e) {
                // Kafka delivery timeout 可能是“broker 已收但 ACK 未回”；即使包在 ExecutionException 中也不能自动重发。
                if (hasAmbiguousDeliveryCause(e)) {
                    markDeliveryUnknown(row, leaseOwner, e);
                } else {
                    handleKnownFailure(row, leaseOwner, e);
                }
            }
        }
        if (!batch.isEmpty()) {
            log.debug("outbox 本轮投递 {} 条", batch.size());
        }
    }

    private void markDeliveryUnknown(OutboxEventRepository.OutboxRow row, String leaseOwner, Throwable failure) {
        String error = abbreviate(failure.toString(), 4000);
        if (outbox.markDeliveryUnknown(row.eventId(), leaseOwner, error)) {
            metrics.outboxDeliveryUnknown(row.topic());
            log.error("outbox 投递结果未知，停止自动重发 eventId={} topic={} err={}",
                    row.eventId(), row.topic(), error);
        } else {
            log.warn("outbox 投递未知结果因租约已失效而忽略 eventId={} topic={}", row.eventId(), row.topic());
        }
    }

    private void handleKnownFailure(OutboxEventRepository.OutboxRow row, String leaseOwner, Throwable failure) {
        String error = abbreviate(failure.toString(), 4000);
        int nextAttempt = row.attempt() + 1;
        if (nextAttempt >= maxAttempts) {
            if (outbox.markFailed(row.eventId(), leaseOwner, error)) {
                metrics.outboxFailed(row.topic());
                log.error("outbox 达到最大尝试次数，进入 FAILED eventId={} topic={} attempts={} err={}",
                        row.eventId(), row.topic(), nextAttempt, error);
            } else {
                log.warn("outbox 失败结果因租约已失效而忽略 eventId={} topic={}", row.eventId(), row.topic());
            }
        } else {
            int backoff = retryBackoff(row.attempt());
            if (outbox.reschedule(row.eventId(), leaseOwner, backoff, error)) {
                log.warn("outbox 发送失败，退避重排 eventId={} topic={} attempt={} backoff={}s err={}",
                        row.eventId(), row.topic(), nextAttempt, backoff, error);
            } else {
                log.warn("outbox 重排因租约已失效而忽略 eventId={} topic={}", row.eventId(), row.topic());
            }
        }
    }

    private boolean hasAmbiguousDeliveryCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof org.apache.kafka.common.errors.TimeoutException
                    || cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private int retryBackoff(int previousFailures) {
        int exponent = Math.min(Math.max(previousFailures, 0), 6);
        return Math.min(300, retryBackoffSeconds * (1 << exponent));
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
