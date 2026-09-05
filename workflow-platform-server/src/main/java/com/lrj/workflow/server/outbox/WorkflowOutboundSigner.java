package com.lrj.workflow.server.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 为 workflow-server 发出的原始 JSON 增加 HMAC，供业务消费方认证 source。 */
@Component
public final class WorkflowOutboundSigner implements InitializingBean {
    static final String SIGNATURE_HEADER = "workflow-signature-v1";
    private static final String SOURCE = "workflow-server";

    private final boolean trustEnabled;
    private final byte[] signingKey;

    public WorkflowOutboundSigner(
            @Value("${workflow.kafka-trust.enabled:false}") boolean trustEnabled,
            @Value("${workflow.kafka-trust.source-signing-keys:}") String sourceSigningKeys) {
        this.trustEnabled = trustEnabled;
        this.signingKey = keyFor(sourceSigningKeys, SOURCE);
    }

    /** 生产信任门禁开启时，禁止以无签名的 workflow-server 身份发布消息。 */
    @Override
    public void afterPropertiesSet() {
        if (signingKey != null && signingKey.length < 32) {
            throw new IllegalStateException("workflow-server HMAC key 解码后必须至少 32 字节");
        }
        if (trustEnabled && signingKey == null) {
            throw new IllegalStateException(
                    "workflow.kafka-trust.source-signing-keys 必须配置至少 32 字节的 workflow-server HMAC key");
        }
    }

    /** 对 outbox 中最终 JSON 字节签名；空配置仅用于关闭信任校验的本地环境。 */
    public ProducerRecord<String, String> record(String topic, String key, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        if (signingKey != null) {
            record.headers().add(new RecordHeader(SIGNATURE_HEADER,
                    sign(signingKey, payload).getBytes(StandardCharsets.US_ASCII)));
        }
        return record;
    }

    private static byte[] keyFor(String mappings, String source) {
        if (mappings == null || mappings.isBlank()) return null;
        for (String pair : mappings.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2 && source.equals(parts[0].trim())) {
                try {
                    return Base64.getUrlDecoder().decode(parts[1].trim());
                } catch (IllegalArgumentException invalidBase64) {
                    throw new IllegalArgumentException("workflow-server signing key must be Base64URL", invalidBase64);
                }
            }
        }
        return null;
    }

    private static String sign(byte[] key, String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("workflow-server Kafka HMAC 初始化失败", failure);
        }
    }
}
