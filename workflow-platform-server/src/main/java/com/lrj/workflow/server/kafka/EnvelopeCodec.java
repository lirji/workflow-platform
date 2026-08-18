package com.lrj.workflow.server.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.workflow.protocol.ProtocolInfo;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 信封 JSON 编解码。his 消费方与本平台都用 String + 显式 ObjectMapper(不依赖 JsonDeserializer 的 trusted.packages)。
 */
@Component
public class EnvelopeCodec {

    private final ObjectMapper mapper;

    public EnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> EventEnvelopeV1<T> parse(String json, Class<T> payloadType, String expectedEventType) {
        try {
            EventEnvelopeV1<T> envelope = mapper.readValue(json,
                    mapper.getTypeFactory().constructParametricType(EventEnvelopeV1.class, payloadType));
            validate(envelope, expectedEventType);
            return envelope;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("解析事件信封失败: " + e.getMessage(), e);
        }
    }

    private static void validate(EventEnvelopeV1<?> envelope, String expectedEventType) {
        require(envelope != null, "事件信封不能为空");
        require(hasText(envelope.eventId()), "eventId 不能为空");
        require(envelope.contractVersion() == ProtocolInfo.CONTRACT_VERSION,
                "contractVersion 不支持: " + envelope.contractVersion());
        require(Objects.equals(expectedEventType, envelope.eventType()),
                "eventType 不匹配: expected=" + expectedEventType + ", actual=" + envelope.eventType());
        require(envelope.occurredAt() != null, "occurredAt 不能为空");
        require(hasText(envelope.source()), "source 不能为空");
        require(hasText(envelope.tenantId()), "tenantId 不能为空");
        require(hasText(envelope.correlationId()), "correlationId 不能为空");
        require(envelope.payload() != null, "payload 不能为空");

        if (envelope.payload() instanceof StartProcessCommandV1 start) {
            require(hasText(start.processDefinitionKey()), "payload.processDefinitionKey 不能为空");
            require(hasText(start.businessKey()), "payload.businessKey 不能为空");
            require(hasText(start.idempotencyKey()), "payload.idempotencyKey 不能为空");
            require(hasText(start.initiator()), "payload.initiator 不能为空");
        } else if (envelope.payload() instanceof WorkflowActionAppliedV1 applied) {
            require(hasText(applied.processInstanceId()), "payload.processInstanceId 不能为空");
            require(hasText(applied.processDefinitionKey()), "payload.processDefinitionKey 不能为空");
            require(hasText(applied.businessKey()), "payload.businessKey 不能为空");
            require(hasText(applied.actionId()), "payload.actionId 不能为空");
            require(applied.status() != null, "payload.status 不能为空");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("事件信封校验失败: " + message);
        }
    }

    public String toJson(Object envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalArgumentException("序列化事件信封失败: " + e.getMessage(), e);
        }
    }
}
