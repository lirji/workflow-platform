package com.lrj.workflow.server.dlq;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.dlq.DlqRecord;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import com.lrj.workflow.server.kafka.KafkaEnvelopeTrustValidator;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 死信重放:把 wf_dlq_event 里 NEW 的消息按原 key/payload 投回原始 topic,由原监听器重新处理,
 * 成功后标记 REPLAYED。原监听器自带 inbox 幂等 → 重放安全。
 */
@Service
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);
    private static final int REPLAY_ALL_CAP = 500;

    private final DlqEventRepository dlq;
    private final KafkaTemplate<String, String> kafka;
    private final WorkflowMetrics metrics;
    private final WorkflowAudit audit;
    private final TransactionTemplate tx;

    public DlqReplayService(DlqEventRepository dlq, KafkaTemplate<String, String> kafka,
                            WorkflowMetrics metrics, WorkflowAudit audit, TransactionTemplate tx) {
        this.dlq = dlq;
        this.kafka = kafka;
        this.metrics = metrics;
        this.audit = audit;
        this.tx = tx;
    }

    public List<DlqRecord> list(String status, int limit) {
        return dlq.findByStatus(status, limit);
    }

    /** 重放单条。返回 false=不存在或已重放。 */
    public boolean replay(long id) {
        Boolean result = tx.execute(status -> {
            DlqRecord rec = dlq.findNewForUpdate(id).orElse(null);
            if (rec == null) {
                return false;
            }
            if (!WorkflowTopics.COMMAND_START.equals(rec.originalTopic())
                    && !WorkflowTopics.ACTION_APPLIED.equals(rec.originalTopic())) {
                throw new IllegalStateException("DLQ 原始 topic 不在允许重放范围: " + rec.originalTopic());
            }
            try {
                ProducerRecord<String, String> outbound = new ProducerRecord<>(
                        rec.originalTopic(), null, null, rec.msgKey(), rec.payload());
                if (rec.signature() != null && !rec.signature().isBlank()) {
                    outbound.headers().add(new RecordHeader(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER,
                            rec.signature().getBytes(StandardCharsets.UTF_8)));
                }
                kafka.send(outbound).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("DLQ 重放发送失败，记录保持 NEW: " + id, e);
            }
            dlq.markReplayed(id);
            metrics.dlqReplayed();
            audit.dlqReplayed(id, rec.originalTopic());
            log.info("死信重放 id={} → topic={} key={}", id, rec.originalTopic(), rec.msgKey());
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /** 批量重放 NEW(上限 {@value #REPLAY_ALL_CAP});返回成功条数。 */
    public int replayAll() {
        int n = 0;
        for (DlqRecord rec : dlq.findByStatus("NEW", REPLAY_ALL_CAP)) {
            if (replay(rec.id())) {
                n++;
            }
        }
        return n;
    }
}
