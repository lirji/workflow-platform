package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 workflow.action.applied:inbox 去重 → 关联回流程 message。回执早到(订阅未就绪)置 WAITING_CORRELATION 重试。
 */
@Component
public class WorkflowActionAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActionAppliedListener.class);

    private final EnvelopeCodec codec;
    private final InboxEventRepository inbox;
    private final MessageCorrelationService correlation;

    public WorkflowActionAppliedListener(EnvelopeCodec codec, InboxEventRepository inbox,
                                         MessageCorrelationService correlation) {
        this.codec = codec;
        this.inbox = inbox;
        this.correlation = correlation;
    }

    @KafkaListener(topics = WorkflowTopics.ACTION_APPLIED, groupId = "workflow-server")
    public void onApplied(String message) {
        EventEnvelopeV1<WorkflowActionAppliedV1> env = codec.parse(message, WorkflowActionAppliedV1.class);
        if (!inbox.tryClaim(env.eventId(), WorkflowTopics.ACTION_APPLIED, env.eventType(), message)) {
            log.debug("重复 applied 事件,跳过 eventId={}", env.eventId());
            return;
        }
        try {
            var outcome = correlation.correlate(env.payload());
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
