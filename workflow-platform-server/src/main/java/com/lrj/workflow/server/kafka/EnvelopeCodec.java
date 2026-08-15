package com.lrj.workflow.server.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import org.springframework.stereotype.Component;

/**
 * 信封 JSON 编解码。his 消费方与本平台都用 String + 显式 ObjectMapper(不依赖 JsonDeserializer 的 trusted.packages)。
 */
@Component
public class EnvelopeCodec {

    private final ObjectMapper mapper;

    public EnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> EventEnvelopeV1<T> parse(String json, Class<T> payloadType) {
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructParametricType(EventEnvelopeV1.class, payloadType));
        } catch (Exception e) {
            throw new IllegalArgumentException("解析事件信封失败: " + e.getMessage(), e);
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
