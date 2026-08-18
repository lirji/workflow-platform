package com.lrj.workflow.server.kafka;

import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaEnvelopeTrustValidatorTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static EventEnvelopeV1<String> envelope(String source, String tenant) {
        return new EventEnvelopeV1<>("event", 1, "type", Instant.now(), source, tenant, "corr", null, "payload");
    }

    @Test
    void disabledKeepsLocalDevelopmentCompatibility() {
        KafkaTrustProperties properties = new KafkaTrustProperties();
        assertThatCode(() -> new KafkaEnvelopeTrustValidator(properties)
                .validate(envelope("unknown", "any"), "json", null)).doesNotThrowAnyException();
    }

    @Test
    void enabledAllowsOnlyExactSourceTenantBinding() {
        KafkaTrustProperties properties = new KafkaTrustProperties();
        properties.setEnabled(true);
        properties.setSourceTenantBindings("his-outpatient=his, his-inpatient=his, risk-engine=risk");
        properties.setSourceSigningKeys("his-outpatient=" + Base64.getUrlEncoder().withoutPadding().encodeToString(KEY)
                + ",his-inpatient=" + Base64.getUrlEncoder().withoutPadding().encodeToString(KEY)
                + ",risk-engine=" + Base64.getUrlEncoder().withoutPadding().encodeToString(KEY));
        KafkaEnvelopeTrustValidator validator = new KafkaEnvelopeTrustValidator(properties);
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
        RecordHeader signature = new RecordHeader(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER, sign("json"));

        assertThatCode(() -> validator.validate(envelope("his-outpatient", "his"), "json", signature))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(envelope("his-outpatient", "risk"), "json", signature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权代表 tenant");
        assertThatThrownBy(() -> validator.validate(envelope("his", "his"), "json", signature))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(envelope("his-outpatient", "his"), "tampered", signature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("签名无效");
    }

    @Test
    void enabledRejectsMissingOrMalformedBindingsAtStartup() {
        KafkaTrustProperties properties = new KafkaTrustProperties();
        properties.setEnabled(true);
        KafkaEnvelopeTrustValidator validator = new KafkaEnvelopeTrustValidator(properties);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source=tenant");

        properties.setSourceTenantBindings("his-outpatient");
        assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    private static byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
