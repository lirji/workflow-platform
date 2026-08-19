package com.lrj.workflow.protocol.api;

import java.util.Map;

/**
 * 通用人工任务办理请求。与审方专用的 {@link CompleteReviewRequest} <b>并存</b>,互不影响:
 * 审方链路继续走 complete-review(decision ∈ PASS/REJECT + 结论网关),
 * 通用审批(OA 请假 / 报销 / 用印等)走本请求。
 *
 * <p>outcome 是<b>自由文本结论</b>(如 APPROVE / REJECT / RETURN / TRANSFER),中台<b>不校验取值</b> ——
 * 取值域由消费方自己的 BPMN 网关解释。中台只负责把它作为流程变量 {@code outcome} 注入。
 * 这是刻意的:中台若枚举结论,每加一种业务结论都要改中台,违背"编排通用、语义归业务"。
 *
 * <p>variables 是随办理写入的业务变量(如 {@code approverChain} 的推进游标)。其中与办理身份、
 * actionId 相关的保留变量名会被服务端<b>忽略并覆盖</b>,防止消费方借 variables 伪造办理人。
 *
 * @param outcome          业务结论,必填
 * @param comment          办理意见,可空
 * @param variables        附加流程变量,可空;保留变量名会被忽略
 * @param actorSub         Casdoor sub(授权主体)
 * @param actorUsername    登录名
 * @param actorDisplayName 展示名,可空
 */
public record CompleteTaskRequest(
        String outcome,
        String comment,
        Map<String, Object> variables,
        String actorSub,
        String actorUsername,
        String actorDisplayName
) {
}
