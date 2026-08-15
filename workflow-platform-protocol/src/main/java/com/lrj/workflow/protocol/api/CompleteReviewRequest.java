package com.lrj.workflow.protocol.api;

/**
 * 审方办理请求。decision ∈ {PASS, REJECT}。actor 三字段:enforce 模式下由 server 从 JWT 派生并覆盖
 * (Phase 3 authz),shadow 镜像模式下由消费方(his)传入可信身份。
 *
 * @param decision        PASS / REJECT
 * @param opinion         审方意见(REJECT 建议必填,由上层校验)
 * @param actorSub        Casdoor sub(授权主体)
 * @param actorUsername   登录名(业务侧回查本地 id)
 * @param actorDisplayName 展示名,可空
 */
public record CompleteReviewRequest(
        String decision,
        String opinion,
        String actorSub,
        String actorUsername,
        String actorDisplayName
) {
}
