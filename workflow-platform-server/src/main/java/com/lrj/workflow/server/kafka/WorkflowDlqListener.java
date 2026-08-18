package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 消费 {@link WorkflowTopics#DLQ}:把死信落库(wf_dlq_event)供人工排查/重放。
 * 原始 topic、异常信息取自 DeadLetterPublishingRecoverer 写入的 DLT header。
 */
@Component
public class WorkflowDlqListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDlqListener.class);

    private final DlqEventRepository dlq;
    private final WorkflowMetrics metrics;

    public WorkflowDlqListener(DlqEventRepository dlq, WorkflowMetrics metrics) {
        this.dlq = dlq;
        this.metrics = metrics;
    }

    @KafkaListener(topics = WorkflowTopics.DLQ, groupId = "workflow-server-dlq",
            containerFactory = "workflowDlqKafkaListenerContainerFactory")
    public void onDlq(ConsumerRecord<String, String> rec) {
        String originalTopic = header(rec, KafkaHeaders.DLT_ORIGINAL_TOPIC, rec.topic());
        String error = header(rec, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null);
        String signature = validSignature(rec);
        String payload = rec.value() == null ? "" : rec.value();
        long id = dlq.save(originalTopic, rec.key(), payload, signature, error);
        metrics.dlqLanded(originalTopic);
        log.warn("死信落库 id={} originalTopic={} key={} error={}", id,
                abbreviate(originalTopic, 256), abbreviate(rec.key(), 256), abbreviate(error, 512));
    }

    private static String header(ConsumerRecord<?, ?> rec, String name, String fallback) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? fallback : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** 仅保留可被入口验证器消费的 32-byte HMAC；畸形 DLT header 降级为 null，不能击穿 DLQ 落库。 */
    private static String validSignature(ConsumerRecord<?, ?> rec) {
        String value = header(rec, KafkaEnvelopeTrustValidator.SIGNATURE_HEADER, null);
        if (value == null || value.length() > 64) {
            return null;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == 32 ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
