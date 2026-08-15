package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.core.process.ProcessApplicationService;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 workflow.command.start:inbox 去重 → 幂等发起流程。start 本身四元组幂等,故至少一次投递安全。
 */
@Component
public class WorkflowStartListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStartListener.class);

    private final EnvelopeCodec codec;
    private final InboxEventRepository inbox;
    private final ProcessApplicationService processApp;

    public WorkflowStartListener(EnvelopeCodec codec, InboxEventRepository inbox,
                                 ProcessApplicationService processApp) {
        this.codec = codec;
        this.inbox = inbox;
        this.processApp = processApp;
    }

    @KafkaListener(topics = WorkflowTopics.COMMAND_START, groupId = "workflow-server")
    public void onStart(String message) {
        EventEnvelopeV1<StartProcessCommandV1> env = codec.parse(message, StartProcessCommandV1.class);
        if (!inbox.tryClaim(env.eventId(), WorkflowTopics.COMMAND_START, env.eventType(), message)) {
            log.debug("重复 start 事件,跳过 eventId={}", env.eventId());
            return;
        }
        try {
            processApp.start(env.tenantId(), env.payload());
            inbox.markDone(env.eventId());
        } catch (Exception e) {
            inbox.delete(env.eventId());   // 允许重投重试
            throw e;
        }
    }
}
