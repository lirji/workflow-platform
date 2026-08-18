package com.lrj.workflow.sdk;

import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * HTTP 实现:RestClient 调 server(:8300)。用于需要即时反馈的查询/办理。
 */
public class RemoteWorkflowClient implements WorkflowClient {

    private final RestClient http;
    private final WorkflowClientProperties props;
    private final WorkflowBearerTokenProvider tokenProvider;

    public RemoteWorkflowClient(WorkflowClientProperties props) {
        this(props, props::getBearerToken);
    }

    public RemoteWorkflowClient(WorkflowClientProperties props, WorkflowBearerTokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getConnectTimeoutMs());
        f.setReadTimeout(props.getReadTimeoutMs());
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(f)
                .requestInterceptor((request, body, execution) -> {
                    String token = currentToken();
                    if (StringUtils.hasText(token)) {
                        request.getHeaders().setBearerAuth(token);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    String currentToken() {
        String token = tokenProvider == null ? null : tokenProvider.currentToken();
        if (StringUtils.hasText(token) && token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7);
        }
        if (!StringUtils.hasText(token) && props.isRequireAuthorization()) {
            throw new IllegalStateException("workflow.client.require-authorization=true，但未取得 Bearer Token");
        }
        return token;
    }

    @Override
    public List<TaskView> findTasks(String tenant, String definitionKey, String businessKey) {
        return http.get().uri(uri -> uri.path("/api/v1/tasks")
                        .queryParamIfPresent("definitionKey", java.util.Optional.ofNullable(definitionKey))
                        .queryParamIfPresent("businessKey", java.util.Optional.ofNullable(businessKey))
                        .build())
                .header("X-Workflow-Tenant", tenant)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<TaskView>>() {
                });
    }

    @Override
    public String completeReview(String tenant, String taskId, CompleteReviewRequest request) {
        Map<?, ?> resp = http.post().uri("/api/v1/tasks/{taskId}/complete-review", taskId)
                .header("X-Workflow-Tenant", tenant)
                .body(request)
                .retrieve()
                .body(Map.class);
        return resp == null ? null : (String) resp.get("actionId");
    }

    @Override
    public void claimTask(String tenant, String taskId, String userId) {
        http.post().uri(uri -> uri.path("/api/v1/tasks/{taskId}/claim").queryParam("userId", userId).build(taskId))
                .header("X-Workflow-Tenant", tenant)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void reassignTask(String tenant, String taskId, String assignee) {
        http.post().uri(uri -> uri.path("/api/v1/tasks/{taskId}/reassign").queryParam("assignee", assignee).build(taskId))
                .header("X-Workflow-Tenant", tenant)
                .retrieve()
                .toBodilessEntity();
    }
}
