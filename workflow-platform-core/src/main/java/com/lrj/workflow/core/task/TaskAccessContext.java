package com.lrj.workflow.core.task;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 由可信认证上下文派生的任务访问主体。core 不依赖 Spring Security，dev/shadow 可显式 bypass。
 */
public record TaskAccessContext(boolean enforced, String principalId, Set<String> authorities) {

    public TaskAccessContext {
        authorities = authorities == null ? Set.of() : authorities.stream()
                .map(a -> a.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static TaskAccessContext disabled() {
        return new TaskAccessContext(false, null, Set.of());
    }

    public static TaskAccessContext enforced(String principalId, Set<String> authorities) {
        return new TaskAccessContext(true, principalId, authorities);
    }

    public boolean isAdmin() {
        return authorities.contains("ADMIN");
    }
}
