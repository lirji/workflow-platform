package com.lrj.workflow.server.outbox;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowOutboundSignerTest {
    private static final byte[] KEY = "workflow-server-signing-key-32b!".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsTheExactOutboxPayloadAsWorkflowServer() throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(KEY);
        WorkflowOutboundSigner signer = new WorkflowOutboundSigner(true,
                "benefit-center=YWJj,workflow-server=" + encoded);
        signer.afterPropertiesSet();

        var record = signer.record("workflow.action.requested.v1", "tenant|definition|business", "{\"x\":1}");
        byte[] actual = record.headers().lastHeader(WorkflowOutboundSigner.SIGNATURE_HEADER).value();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal("{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThat(new String(actual, StandardCharsets.US_ASCII)).isEqualTo(expected);
    }

    @Test
    void trustEnabledFailsFastWithoutWorkflowServerKey() {
        WorkflowOutboundSigner signer = new WorkflowOutboundSigner(true, "benefit-center=YWJj");

        assertThatThrownBy(signer::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow-server");
    }

    @Test
    void rejectsConfiguredShortKeyEvenWhenLocalTrustIsDisabled() {
        String shortKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        WorkflowOutboundSigner signer = new WorkflowOutboundSigner(false, "workflow-server=" + shortKey);

        assertThatThrownBy(signer::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void localTrustDisabledAllowsUnsignedRecords() {
        WorkflowOutboundSigner signer = new WorkflowOutboundSigner(false, "");

        assertThatCode(signer::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(signer.record("topic", "key", "{} ").headers()
                .lastHeader(WorkflowOutboundSigner.SIGNATURE_HEADER)).isNull();
    }
}
