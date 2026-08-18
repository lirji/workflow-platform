package com.lrj.workflow.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 鉴权配置,前缀 {@code workflow.security}。enabled 默认 false —— 保 shadow 联调(明文头路径)不破;
 * 生产置 true 接 Casdoor JWT(与前端 VITE_AUTH_ENABLED 分期对齐)。
 */
@ConfigurationProperties(prefix = "workflow.security")
public class WorkflowSecurityProperties {

    /** 是否启用 JWT 鉴权。false=放行(仅 dev/shadow);true=OAuth2 Resource Server 校验。 */
    private boolean enabled = false;

    /** Casdoor OIDC issuer(用于 issuer-uri 方式构建 JwtDecoder,启动时拉 openid-config)。 */
    private String issuerUri = "";

    /** Casdoor JWKS 地址(优先;lazy 拉取,不阻塞启动)。issuer/jwks 二者配其一。 */
    private String jwkSetUri = "";

    /** JWT 中承载组/角色的 claim 名(Casdoor 默认 groups)。 */
    private String groupsClaim = "groups";

    /** 访问令牌必须包含的 audience；生产 profile 强制非空。 */
    private String audience = "";

    /** JWT 中承载租户的 claim 名；生产 profile 强制非空，dev 未配置时回退明文 Header。 */
    private String tenantClaim = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }
}
