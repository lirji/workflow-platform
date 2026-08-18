package com.lrj.workflow.server.kafka;

import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** 校验某个已认证/获 ACL 放行的逻辑 source 是否获准代表 envelope 中的 tenant。 */
@Component
public class KafkaEnvelopeTrustValidator implements InitializingBean {

    public static final String SIGNATURE_HEADER = "workflow-signature-v1";

    private final KafkaTrustProperties properties;

    public KafkaEnvelopeTrustValidator(KafkaTrustProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            return;
        }
        String bindings = properties.getSourceTenantBindings();
        if (bindings == null || bindings.isBlank()
                || Arrays.stream(bindings.split(",")).map(String::trim).anyMatch(this::malformed)) {
            throw new IllegalStateException(
                    "workflow.kafka-trust.source-tenant-bindings 必须是逗号分隔的 source=tenant");
        }
        for (String binding : bindings.split(",")) {
            String source = binding.trim().substring(0, binding.trim().indexOf('='));
            byte[] key = signingKey(source);
            if (key == null || key.length < 32) {
                throw new IllegalStateException(
                        "workflow.kafka-trust.source-signing-keys 必须为每个 source 配置至少 32 字节的 Base64URL HMAC key");
            }
        }
    }

    /** Kafka 首次入站：声明 allowlist + 原始消息 HMAC，两者都通过才可进入 inbox。 */
    public void validate(EventEnvelopeV1<?> envelope, String rawMessage, Header signatureHeader) {
        if (!properties.isEnabled()) {
            return;
        }
        validateDeclaredBinding(envelope);
        byte[] key = signingKey(envelope.source());
        byte[] supplied;
        try {
            supplied = signatureHeader == null ? null : Base64.getUrlDecoder().decode(signatureHeader.value());
        } catch (IllegalArgumentException e) {
            supplied = null;
        }
        byte[] expected = hmac(key, rawMessage);
        if (supplied == null || !MessageDigest.isEqual(expected, supplied)) {
            throw new IllegalArgumentException("Kafka 消息签名无效: source=" + envelope.source());
        }
    }

    /** 仅供已经通过首次 HMAC、持久化在 inbox 的关联重试再次校验声明策略。 */
    public void validateStoredEnvelope(EventEnvelopeV1<?> envelope) {
        if (properties.isEnabled()) {
            validateDeclaredBinding(envelope);
        }
    }

    private void validateDeclaredBinding(EventEnvelopeV1<?> envelope) {
        String expected = envelope.source() + "=" + envelope.tenantId();
        String bindings = properties.getSourceTenantBindings() == null ? "" : properties.getSourceTenantBindings();
        boolean allowed = Arrays.stream(bindings.split(","))
                .map(String::trim)
                .filter(binding -> !binding.isEmpty())
                .anyMatch(expected::equals);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Kafka source 无权代表 tenant: source=" + envelope.source() + ", tenant=" + envelope.tenantId());
        }
    }

    private boolean malformed(String binding) {
        int equals = binding.indexOf('=');
        return equals <= 0 || equals != binding.lastIndexOf('=') || equals == binding.length() - 1
                || binding.substring(0, equals).isBlank() || binding.substring(equals + 1).isBlank();
    }

    private byte[] signingKey(String source) {
        String configured = properties.getSourceSigningKeys();
        if (configured == null) {
            return null;
        }
        for (String entry : configured.split(",")) {
            String value = entry.trim();
            int equals = value.indexOf('=');
            if (equals > 0 && source.equals(value.substring(0, equals).trim())) {
                try {
                    return Base64.getUrlDecoder().decode(value.substring(equals + 1).trim());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private byte[] hmac(byte[] key, String rawMessage) {
        if (key == null || rawMessage == null) {
            return new byte[0];
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(rawMessage.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Kafka HMAC 校验失败", e);
        }
    }
}
