package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费 workflow.action.applied:inbox 去重 → 关联回流程 message。回执早到(订阅未就绪)置 WAITING_CORRELATION 重试。
 */
@Component
public class WorkflowActionAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActionAppliedListener.class);

    private final EnvelopeCodec codec;
    private final InboxEventRepository inbox;
    private final MessageCorrelationService correlation;
    private final WorkflowMetrics metrics;
    private final LifecyclePublisher lifecycle;
    private final KafkaEnvelopeTrustValidator trust;

    public WorkflowActionAppliedListener(EnvelopeCodec codec, InboxEventRepository inbox,
                                         MessageCorrelationService correlation, WorkflowMetrics metrics,
                                         LifecyclePublisher lifecycle, KafkaEnvelopeTrustValidator trust) {
        this.codec = codec;
        this.inbox = inbox;
        this.correlation = correlation;
        this.metrics = metrics;
        this.lifecycle = lifecycle;
        this.trust = trust;
    }

    @KafkaListener(topics = WorkflowTopics.ACTION_APPLIED, groupId = "workflow-server")
    @Transactional
    public void onApplied(ConsumerRecord<String, String> record) {
        String message = record.value();
        EventEnvelopeV1<WorkflowActionAppliedV1> env = codec.parse(
                message, WorkflowActionAppliedV1.class, WorkflowTopics.ACTION_APPLIED);
        trust.validate(env, message, record.headers().lastHeader(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER));
        if (!inbox.tryClaim(env.eventId(), WorkflowTopics.ACTION_APPLIED, env.eventType(), message)) {
            log.debug("重复 applied 事件,跳过 eventId={}", env.eventId());
            return;
        }
        try {
            var outcome = correlation.correlate(env.tenantId(), env.payload());
            metrics.correlationOutcome(outcome.name());
            if (outcome == MessageCorrelationService.Outcome.CORRELATED && env.payload().status() != null) {
                var p = env.payload();
                metrics.actionApplied(p.status().name());
                String lc = switch (p.status()) {
                    case APPLIED -> "COMPLETED";
                    case FAILED_FINAL -> "INCIDENT";
                    default -> null;
                };
                if (lc != null) {
                    lifecycle.publish(env.tenantId(), p.processInstanceId(), p.processDefinitionKey(), p.businessKey(), lc);
                }
            }
            if (outcome == MessageCorrelationService.Outcome.WAITING_SUBSCRIPTION) {
                inbox.markWaitingCorrelation(env.eventId(), 5, "message 订阅未就绪");
            } else {
                inbox.markDone(env.eventId());
            }
        } catch (Exception e) {
            inbox.delete(env.eventId());
            throw e;
        }
    }
}
