package com.lrj.workflow.server.outbox;

import com.lrj.workflow.core.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 事务性发件箱投递:定时领取 READY 事件(SKIP LOCKED)→ 发 Kafka → markSent;失败退避重排。
 * 至少一次投递;消费方按 eventId(inbox)去重收敛为恰好一次处理。
 */
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String owner = "wf-" + UUID.randomUUID();

    @Value("${workflow.outbox.batch-size:100}")
    int batchSize;
    @Value("${workflow.outbox.lease-seconds:30}")
    int leaseSeconds;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${workflow.outbox.poll-ms:1000}")
    public void publishBatch() {
        List<OutboxEventRepository.OutboxRow> batch = outbox.claimBatch(batchSize, owner, leaseSeconds);
        for (var row : batch) {
            try {
                kafka.send(row.topic(), row.msgKey(), row.payload()).get();
                outbox.markSent(row.eventId());
            } catch (Exception e) {
                log.warn("outbox 发送失败,退避重排 eventId={} topic={} err={}",
                        row.eventId(), row.topic(), e.toString());
                outbox.reschedule(row.eventId(), 5);
            }
        }
        if (!batch.isEmpty()) {
            log.debug("outbox 本轮投递 {} 条", batch.size());
        }
    }
}
