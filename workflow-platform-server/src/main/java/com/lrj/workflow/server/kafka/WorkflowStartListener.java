package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.process.ProcessApplicationService;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费 workflow.command.start:inbox 去重 → 幂等发起流程。start 本身四元组幂等,故至少一次投递安全。
 */
@Component
public class WorkflowStartListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStartListener.class);

    private final EnvelopeCodec codec;
    private final InboxEventRepository inbox;
    private final ProcessApplicationService processApp;
    private final WorkflowMetrics metrics;
    private final LifecyclePublisher lifecycle;
    private final KafkaEnvelopeTrustValidator trust;

    public WorkflowStartListener(EnvelopeCodec codec, InboxEventRepository inbox,
                                 ProcessApplicationService processApp, WorkflowMetrics metrics,
                                 LifecyclePublisher lifecycle, KafkaEnvelopeTrustValidator trust) {
        this.codec = codec;
        this.inbox = inbox;
        this.processApp = processApp;
        this.metrics = metrics;
        this.lifecycle = lifecycle;
        this.trust = trust;
    }

    @KafkaListener(topics = WorkflowTopics.COMMAND_START, groupId = "workflow-server")
    @Transactional
    public void onStart(ConsumerRecord<String, String> record) {
        String message = record.value();
        EventEnvelopeV1<StartProcessCommandV1> env = codec.parse(
                message, StartProcessCommandV1.class, WorkflowTopics.COMMAND_START);
        trust.validate(env, message, record.headers().lastHeader(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER));
        if (!inbox.tryClaim(env.eventId(), WorkflowTopics.COMMAND_START, env.eventType(), message)) {
            log.debug("重复 start 事件,跳过 eventId={}", env.eventId());
            return;
        }
        try {
            ProcessLink link = processApp.start(env.tenantId(), env.payload());
            metrics.processStarted(env.tenantId(), env.payload().processDefinitionKey());
            lifecycle.publish(env.tenantId(), link.processInstanceId(), link.processDefinitionKey(), link.businessKey(), "STARTED");
            inbox.markDone(env.eventId());
        } catch (Exception e) {
            inbox.delete(env.eventId());   // 允许重投重试
            throw e;
        }
    }
}
