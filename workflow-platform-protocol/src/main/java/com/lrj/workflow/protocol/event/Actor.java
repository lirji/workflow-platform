package com.lrj.workflow.protocol.event;

/**
 * 办理人主体快照。授权主体一律用 Casdoor {@code subjectId}(JWT sub);
 * username/displayName 仅作展示与业务侧可信回查(服务端 crosswalk),不可当授权真相。
 *
 * @param subjectId   Casdoor sub(授权主体)
 * @param username    登录名(业务侧回查本地数值 id 用)
 * @param displayName 展示名,可空
 */
public record Actor(String subjectId, String username, String displayName) {
}
