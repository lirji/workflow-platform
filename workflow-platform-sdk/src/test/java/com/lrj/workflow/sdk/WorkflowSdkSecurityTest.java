package com.lrj.workflow.sdk;

import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSdkSecurityTest {

    @Test
    void tokenProviderIsEvaluatedPerRequestAndBearerPrefixIsNormalized() {
        WorkflowClientProperties props = new WorkflowClientProperties();
        props.setRequireAuthorization(true);
        AtomicReference<String> token = new AtomicReference<>("first");
        RemoteWorkflowClient client = new RemoteWorkflowClient(props, token::get);

        assertThat(client.currentToken()).isEqualTo("first");
        token.set("Bearer second");
        assertThat(client.currentToken()).isEqualTo("second");
    }

    @Test
    void requiredAuthorizationFailsBeforeAnonymousRequest() {
        WorkflowClientProperties props = new WorkflowClientProperties();
        props.setRequireAuthorization(true);
        RemoteWorkflowClient client = new RemoteWorkflowClient(props, () -> "");

        assertThatThrownBy(client::currentToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bearer Token");
    }

    @Test
    void remoteRequestCarriesBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/tasks", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WorkflowClientProperties props = new WorkflowClientProperties();
            props.setBaseUrl("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
            props.setRequireAuthorization(true);
            RemoteWorkflowClient client = new RemoteWorkflowClient(props, () -> "service-token");

            assertThat(client.findTasks("his", null, null)).isEmpty();
            assertThat(authorization.get()).isEqualTo("Bearer service-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noopCanFailFastOnWriteOperations() {
        NoopWorkflowClient client = new NoopWorkflowClient(true);
        CompleteReviewRequest request = new CompleteReviewRequest("PASS", null, null, null, null);

        assertThatThrownBy(() -> client.completeReview("his", "t1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
    }
}
