package com.lrj.workflow.server.correlation;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import com.lrj.workflow.server.kafka.EnvelopeCodec;
import com.lrj.workflow.server.kafka.KafkaEnvelopeTrustValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关联重试:回执早到而 message 订阅当时未就绪的 ACK,在此按退避重放,直到 message catch 就绪推进流程。
 */
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CorrelationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(CorrelationRetryJob.class);
    private final String owner = "correlation-" + java.util.UUID.randomUUID();

    private final InboxEventRepository inbox;
    private final MessageCorrelationService correlation;
    private final EnvelopeCodec codec;
    private final KafkaEnvelopeTrustValidator trust;

    public CorrelationRetryJob(InboxEventRepository inbox, MessageCorrelationService correlation,
                               EnvelopeCodec codec, KafkaEnvelopeTrustValidator trust) {
        this.inbox = inbox;
        this.correlation = correlation;
        this.codec = codec;
        this.trust = trust;
    }

    @Scheduled(fixedDelayString = "${workflow.correlation.retry-ms:3000}")
    public void retry() {
        for (String eventId : inbox.claimDueWaitingCorrelation(50, owner, 30)) {
            try {
                var env = codec.parse(inbox.getPayload(eventId), WorkflowActionAppliedV1.class,
                        WorkflowTopics.ACTION_APPLIED);
                trust.validateStoredEnvelope(env);
                var outcome = correlation.correlate(env.tenantId(), env.payload());
                if (outcome == MessageCorrelationService.Outcome.WAITING_SUBSCRIPTION) {
                    inbox.markWaitingCorrelation(eventId, 5, "仍在等 message 订阅");
                } else {
                    inbox.markDone(eventId);
                    log.info("关联重试成功 eventId={} outcome={}", eventId, outcome);
                }
            } catch (Exception e) {
                log.warn("关联重试异常 eventId={} err={}", eventId, e.toString());
                inbox.markWaitingCorrelation(eventId, 10, e.toString());
            }
        }
    }
}
