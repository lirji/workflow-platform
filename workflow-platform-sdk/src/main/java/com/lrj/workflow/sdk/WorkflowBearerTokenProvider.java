package com.lrj.workflow.sdk;

/**
 * 为 SDK 的每次 HTTP 请求提供当前 OAuth2 access token。实现方可接入 client-credentials 或 token relay。
 * 返回裸 token（不含 {@code Bearer } 前缀）。
 */
@FunctionalInterface
public interface WorkflowBearerTokenProvider {

    String currentToken();
}
