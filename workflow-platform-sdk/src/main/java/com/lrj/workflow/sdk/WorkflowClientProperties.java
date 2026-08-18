package com.lrj.workflow.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SDK 配置,前缀 {@code workflow.client}。enabled 默认 false —— 引入即安全,消费方显式开启才走远程。
 */
@ConfigurationProperties(prefix = "workflow.client")
public class WorkflowClientProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8300";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;
    /** 可选静态 token；生产更推荐提供 WorkflowBearerTokenProvider bean 动态取 token。 */
    private String bearerToken = "";
    /** true 时每次远程请求缺 token 立即失败，不发送匿名请求。 */
    private boolean requireAuthorization = false;
    /** SDK disabled 时写操作抛错，避免生产误配置被 Noop 静默吞掉。 */
    private boolean failOnDisabledWrites = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public boolean isRequireAuthorization() {
        return requireAuthorization;
    }

    public void setRequireAuthorization(boolean requireAuthorization) {
        this.requireAuthorization = requireAuthorization;
    }

    public boolean isFailOnDisabledWrites() {
        return failOnDisabledWrites;
    }

    public void setFailOnDisabledWrites(boolean failOnDisabledWrites) {
        this.failOnDisabledWrites = failOnDisabledWrites;
    }
}
