package com.lrj.workflow.server.security;

import com.lrj.workflow.protocol.event.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 有效身份解析:鉴权启用且携带 JWT 时,tenant/actor **从 JWT 派生并覆盖**明文头/请求体(可信真相);
 * 否则回退到明文头 / 请求体(dev/shadow 联调路径)。集中一处,便于 Controller 统一取用。
 */
@Component
public class WorkflowIdentityResolver {

    private final WorkflowSecurityProperties props;

    public WorkflowIdentityResolver(WorkflowSecurityProperties props) {
        this.props = props;
    }

    /** 租户:JWT 配了 tenant-claim 且存在则取之,否则用明文头(不臆造 Casdoor 租户映射)。 */
    public String tenant(String headerTenant) {
        Jwt jwt = currentJwt();
        if (jwt != null && StringUtils.hasText(props.getTenantClaim())) {
            String t = jwt.getClaimAsString(props.getTenantClaim());
            if (StringUtils.hasText(t)) {
                return t;
            }
        }
        return headerTenant;
    }

    /** 办理人:JWT 存在则由 sub/preferred_username/name 派生(覆盖请求体传入的 actor),否则用请求体。 */
    public Actor actor(Actor bodyActor) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return bodyActor;
        }
        String username = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"), jwt.getSubject());
        String displayName = firstNonBlank(jwt.getClaimAsString("name"), username);
        return new Actor(jwt.getSubject(), username, displayName);
    }

    private Jwt currentJwt() {
        if (!props.isEnabled()) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }
}
