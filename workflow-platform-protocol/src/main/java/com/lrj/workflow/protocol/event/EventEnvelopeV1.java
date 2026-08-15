package com.lrj.workflow.protocol.event;

import java.time.Instant;

/**
 * 所有中台 Kafka 事件的统一信封(Published Language 契约,主版本 1)。
 *
 * <p>消费者必须先按 {@link #eventId} 做 inbox 幂等去重,再按业务 actionId 去重(见 {@link WorkflowActionRequestedV1})。
 *
 * @param eventId        全局唯一事件 id(UUID 字符串),inbox 去重键
 * @param contractVersion 契约主版本,固定 1
 * @param eventType      事件类型标识(如 {@code workflow.action.requested.v1})
 * @param occurredAt     产生时间
 * @param source         来源系统标识(如 {@code workflow-server} / {@code his-outpatient})
 * @param tenantId       租户(his/auth/risk)
 * @param correlationId  关联链路 id(通常贯穿一次业务处理)
 * @param causationId    触发本事件的上游 eventId,可空
 * @param payload        具体载荷
 * @param <T>            载荷类型
 */
public record EventEnvelopeV1<T>(
        String eventId,
        int contractVersion,
        String eventType,
        Instant occurredAt,
        String source,
        String tenantId,
        String correlationId,
        String causationId,
        T payload
) {
}
