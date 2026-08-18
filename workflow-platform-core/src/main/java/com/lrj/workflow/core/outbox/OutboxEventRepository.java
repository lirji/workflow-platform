package com.lrj.workflow.core.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * wf_outbox_event 访问。业务/流程写事件与本条 insert 在同一 PG 事务(事务性发件箱),
 * 后台 publisher 用 {@code FOR UPDATE SKIP LOCKED} 领取后发 Kafka；明确失败自动重试，
 * 模糊结果停在 DELIVERY_UNKNOWN 等待人工核账，不能把它过度宣称为无条件至少一次。
 */
@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbc;

    public OutboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(String eventId, String topic, String msgKey, String eventType, String payload,
                            int attempt) {
    }

    private static final RowMapper<OutboxRow> MAPPER = (rs, n) -> new OutboxRow(
            rs.getString("event_id"), rs.getString("topic"), rs.getString("msg_key"),
            rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempt"));

    /** 与调用方同事务写入一条待发事件。payload 为 JSON 字符串。 */
    public void enqueue(String eventId, String topic, String msgKey, String eventType, String payloadJson) {
        jdbc.update(
                "INSERT INTO wf_outbox_event(event_id,topic,msg_key,event_type,payload,status) "
                        + "VALUES (?,?,?,?,?::jsonb,'READY')",
                eventId, topic, msgKey, eventType, payloadJson);
    }

    /** 领取一批可发事件(SKIP LOCKED 避免多实例重复扫描);同事务内置 PROCESSING + 租约。 */
    public List<OutboxRow> claimBatch(int limit, String leaseOwner, int leaseSeconds) {
        return jdbc.query(
                "UPDATE wf_outbox_event SET status='PROCESSING', lease_owner=?, "
                        + "lease_until=now() + (? * interval '1 second'), updated_at=now() "
                        + "WHERE event_id IN ("
                        + "  SELECT event_id FROM wf_outbox_event "
                        + "  WHERE status IN ('READY','PROCESSING') AND available_at <= now() "
                        + "    AND (lease_until IS NULL OR lease_until < now()) "
                        + "  ORDER BY available_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING event_id, topic, msg_key, event_type, payload, attempt",
                MAPPER, leaseOwner, leaseSeconds, limit);
    }

    /** 每条发送前续租；租约已过期或已被接管时返回 false，调用方不得再向 Kafka 发送。 */
    public boolean renewLease(String eventId, String leaseOwner, int leaseSeconds) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET lease_until=now() + (? * interval '1 second'), updated_at=now() "
                        + "WHERE event_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>=now()",
                leaseSeconds, eventId, leaseOwner) == 1;
    }

    /** 只有当前租约持有者可提交发送成功；false 表示租约已过期/被接管，调用方不得覆盖新 owner 的结果。 */
    public boolean markSent(String eventId, String leaseOwner) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET status='SENT', lease_owner=NULL, lease_until=NULL, "
                        + "last_error=NULL, updated_at=now() "
                        + "WHERE event_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>=now()",
                eventId, leaseOwner) == 1;
    }

    /** 发送失败但未达上限：仅当前租约持有者可释放并退避重排。 */
    public boolean reschedule(String eventId, String leaseOwner, int backoffSeconds, String error) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET status='READY', attempt=attempt+1, lease_owner=NULL, lease_until=NULL, "
                        + "last_error=?, available_at=now() + (? * interval '1 second'), updated_at=now() "
                        + "WHERE event_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>=now()",
                error, backoffSeconds, eventId, leaseOwner) == 1;
    }

    /** 达到最大尝试次数：仅当前租约持有者可进入 FAILED 终态。 */
    public boolean markFailed(String eventId, String leaseOwner, String error) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET status='FAILED', attempt=attempt+1, lease_owner=NULL, lease_until=NULL, "
                        + "last_error=?, updated_at=now() "
                        + "WHERE event_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>=now()",
                error, eventId, leaseOwner) == 1;
    }

    /**
     * 应用等待超时/线程中断不等于 broker 确认失败；停止自动重发并标记投递结果未知，避免把可能已送达的消息写成 FAILED。
     */
    public boolean markDeliveryUnknown(String eventId, String leaseOwner, String error) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET status='DELIVERY_UNKNOWN', attempt=attempt+1, "
                        + "lease_owner=NULL, lease_until=NULL, last_error=?, updated_at=now() "
                        + "WHERE event_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>=now()",
                error, eventId, leaseOwner) == 1;
    }

    /**
     * 运维核账确认后，把结果未知事件以同一 eventId 受控放回 READY；绝不由 publisher 自动调用。
     */
    public boolean requeueDeliveryUnknown(String tenant, String eventId, String reason) {
        return jdbc.update(
                "UPDATE wf_outbox_event SET status='READY', lease_owner=NULL, lease_until=NULL, "
                        + "available_at=now(), last_error=right(coalesce(last_error,'') "
                        + "|| E'\\nmanual-requeue: ' || ?, 4000), updated_at=now() "
                        + "WHERE event_id=? AND status='DELIVERY_UNKNOWN' AND payload->>'tenantId'=?",
                reason, eventId, tenant) == 1;
    }

    public int countByStatus(String status) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM wf_outbox_event WHERE status=?", Integer.class, status);
        return n == null ? 0 : n;
    }
}
