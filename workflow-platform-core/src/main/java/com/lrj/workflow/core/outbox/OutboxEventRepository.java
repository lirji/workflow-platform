package com.lrj.workflow.core.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * wf_outbox_event 访问。业务/流程写事件与本条 insert 在同一 PG 事务(事务性发件箱),
 * 后台 publisher 用 {@code FOR UPDATE SKIP LOCKED} 领取后发 Kafka(至少一次)。
 */
@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbc;

    public OutboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(String eventId, String topic, String msgKey, String eventType, String payload) {
    }

    private static final RowMapper<OutboxRow> MAPPER = (rs, n) -> new OutboxRow(
            rs.getString("event_id"), rs.getString("topic"), rs.getString("msg_key"),
            rs.getString("event_type"), rs.getString("payload"));

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
                        + "  ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT ?) "
                        + "RETURNING event_id, topic, msg_key, event_type, payload",
                MAPPER, leaseOwner, leaseSeconds, limit);
    }

    public void markSent(String eventId) {
        jdbc.update("UPDATE wf_outbox_event SET status='SENT', updated_at=now() WHERE event_id=?", eventId);
    }

    /** 发送失败:退避重排(指数退避由调用方给出 backoffSeconds)。超阈值可另置 FAILED 进 DLQ(Phase 3)。 */
    public void reschedule(String eventId, int backoffSeconds) {
        jdbc.update(
                "UPDATE wf_outbox_event SET status='READY', attempt=attempt+1, lease_owner=NULL, lease_until=NULL, "
                        + "available_at=now() + (? * interval '1 second'), updated_at=now() WHERE event_id=?",
                backoffSeconds, eventId);
    }

    public int countByStatus(String status) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM wf_outbox_event WHERE status=?", Integer.class, status);
        return n == null ? 0 : n;
    }
}
