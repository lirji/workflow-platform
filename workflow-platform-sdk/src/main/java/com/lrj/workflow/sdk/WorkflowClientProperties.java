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
}
