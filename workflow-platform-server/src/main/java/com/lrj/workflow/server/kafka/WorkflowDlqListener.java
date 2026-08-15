package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 消费 {@link WorkflowTopics#DLQ}:把死信落库(wf_dlq_event)供人工排查/重放。
 * 原始 topic、异常信息取自 DeadLetterPublishingRecoverer 写入的 DLT header。
 */
@Component
public class WorkflowDlqListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDlqListener.class);

    private final DlqEventRepository dlq;

    public WorkflowDlqListener(DlqEventRepository dlq) {
        this.dlq = dlq;
    }

    @KafkaListener(topics = WorkflowTopics.DLQ, groupId = "workflow-server-dlq")
    public void onDlq(ConsumerRecord<String, String> rec) {
        String originalTopic = header(rec, KafkaHeaders.DLT_ORIGINAL_TOPIC, rec.topic());
        String error = header(rec, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null);
        long id = dlq.save(originalTopic, rec.key(), rec.value(), error);
        log.warn("死信落库 id={} originalTopic={} key={} error={}", id, originalTopic, rec.key(), error);
    }

    private static String header(ConsumerRecord<?, ?> rec, String name, String fallback) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? fallback : new String(h.value(), StandardCharsets.UTF_8);
    }
}
