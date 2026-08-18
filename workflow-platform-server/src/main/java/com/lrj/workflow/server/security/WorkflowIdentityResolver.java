package com.lrj.workflow.server.security;

import com.lrj.workflow.core.WorkflowAccessDeniedException;
import com.lrj.workflow.core.task.TaskAccessContext;
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

    /**
     * 租户：配置 tenant claim 后只信任 JWT，claim 缺失或 Header 冲突均 fail closed。
     * 仅未配置 tenant claim 的 dev/shadow 路径允许明文 Header。
     */
    public String tenant(String headerTenant) {
        Jwt jwt = currentJwt();
        if (jwt != null && StringUtils.hasText(props.getTenantClaim())) {
            String t = jwt.getClaimAsString(props.getTenantClaim());
            if (!StringUtils.hasText(t)) {
                throw new WorkflowAccessDeniedException("访问令牌缺少租户声明");
            }
            if (StringUtils.hasText(headerTenant) && !t.equals(headerTenant)) {
                throw new WorkflowAccessDeniedException("请求租户与访问令牌不一致");
            }
            return t;
        }
        if (!StringUtils.hasText(headerTenant)) {
            throw new IllegalArgumentException("缺少 X-Workflow-Tenant，且访问令牌未提供租户声明");
        }
        return headerTenant;
    }

    /** 当前任务访问上下文；未启用安全时返回兼容 dev/shadow 的 bypass。 */
    public TaskAccessContext taskAccess() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return TaskAccessContext.disabled();
        }
        String principal = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
        java.util.Set<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(a -> a.getAuthority().toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return TaskAccessContext.enforced(principal, authorities);
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
