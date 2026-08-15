package com.lrj.workflow.server.kafka;

import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.WorkflowLifecycleV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 生命周期通知发布(topic workflow.lifecycle.v1)。**best-effort**:不参与正确性,发布失败仅告警不影响主流程。
 * 直发 Kafka(不走 outbox),供外部观察者/看板订阅。
 */
@Component
public class LifecyclePublisher {

    private static final Logger log = LoggerFactory.getLogger(LifecyclePublisher.class);

    private final EnvelopeCodec codec;
    private final KafkaTemplate<String, String> kafka;

    public LifecyclePublisher(EnvelopeCodec codec, KafkaTemplate<String, String> kafka) {
        this.codec = codec;
        this.kafka = kafka;
    }

    public void publish(String tenant, String processInstanceId, String definitionKey, String businessKey, String lifecycle) {
        try {
            var payload = new WorkflowLifecycleV1(processInstanceId, definitionKey, businessKey, lifecycle);
            var env = new EventEnvelopeV1<>(UUID.randomUUID().toString(), 1, WorkflowTopics.LIFECYCLE,
                    Instant.now(), "workflow-server", tenant, processInstanceId, null, payload);
            kafka.send(WorkflowTopics.LIFECYCLE, key(tenant, definitionKey, businessKey), codec.toJson(env));
        } catch (Exception e) {
            log.warn("lifecycle 发布失败(best-effort,忽略) pid={} lifecycle={}: {}", processInstanceId, lifecycle, e.getMessage());
        }
    }

    private static String key(String tenant, String def, String biz) {
        return tenant + "|" + def + "|" + biz;
    }
}
