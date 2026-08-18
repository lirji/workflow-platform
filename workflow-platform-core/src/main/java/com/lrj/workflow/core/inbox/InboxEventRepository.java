package com.lrj.workflow.core.inbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * wf_inbox_event 访问。eventId 为主键 → 天然幂等去重(至少一次投递收敛为恰好一次处理)。
 */
@Repository
public class InboxEventRepository {

    private final JdbcTemplate jdbc;

    public InboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试登记一条入站事件。返回 true=首次(继续处理);false=已存在(重复,跳过)。
     */
    public boolean tryClaim(String eventId, String topic, String eventType, String payload) {
        try {
            jdbc.update(
                    "INSERT INTO wf_inbox_event(event_id,topic,event_type,payload,status) "
                            + "VALUES (?,?,?,?,'PROCESSING')",
                    eventId, topic, eventType, payload);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 允许失败事件被重新投递:删除 in-flight 记录(重投时可重新 claim)。 */
    public void delete(String eventId) {
        jdbc.update("DELETE FROM wf_inbox_event WHERE event_id=?", eventId);
    }

    /** 取原始 payload(WAITING_CORRELATION 重放用)。 */
    public String getPayload(String eventId) {
        return jdbc.queryForObject("SELECT payload FROM wf_inbox_event WHERE event_id=?", String.class, eventId);
    }

    public void markDone(String eventId) {
        jdbc.update("UPDATE wf_inbox_event SET status='DONE', lease_owner=NULL, lease_until=NULL, updated_at=now() "
                + "WHERE event_id=?", eventId);
    }

    /** 回执早到:message 订阅尚未就绪,置 WAITING_CORRELATION 指数退避重试(不丢弃)。 */
    public void markWaitingCorrelation(String eventId, int backoffSeconds, String error) {
        jdbc.update(
                "UPDATE wf_inbox_event SET status='WAITING_CORRELATION', attempt=attempt+1, "
                        + "next_retry_at=now() + (? * interval '1 second'), error=?, "
                        + "lease_owner=NULL, lease_until=NULL, updated_at=now() WHERE event_id=?",
                backoffSeconds, error, eventId);
    }

    public void markFailed(String eventId, String error) {
        jdbc.update("UPDATE wf_inbox_event SET status='FAILED', error=?, lease_owner=NULL, lease_until=NULL, "
                        + "updated_at=now() WHERE event_id=?",
                error, eventId);
    }

    /**
     * 多副本安全地领取到期关联任务。租约到期可由其它副本接管，SKIP LOCKED 避免同批重复处理。
     */
    public java.util.List<String> claimDueWaitingCorrelation(int limit, String leaseOwner, int leaseSeconds) {
        return jdbc.queryForList(
                "UPDATE wf_inbox_event SET lease_owner=?, "
                        + "lease_until=now() + (? * interval '1 second'), updated_at=now() "
                        + "WHERE event_id IN ("
                        + " SELECT event_id FROM wf_inbox_event WHERE status='WAITING_CORRELATION' "
                        + " AND (next_retry_at IS NULL OR next_retry_at <= now()) "
                        + " AND (lease_until IS NULL OR lease_until < now()) "
                        + " ORDER BY next_retry_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING event_id",
                String.class, leaseOwner, leaseSeconds, limit);
    }
}
