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
        jdbc.update("UPDATE wf_inbox_event SET status='DONE', updated_at=now() WHERE event_id=?", eventId);
    }

    /** 回执早到:message 订阅尚未就绪,置 WAITING_CORRELATION 指数退避重试(不丢弃)。 */
    public void markWaitingCorrelation(String eventId, int backoffSeconds, String error) {
        jdbc.update(
                "UPDATE wf_inbox_event SET status='WAITING_CORRELATION', attempt=attempt+1, "
                        + "next_retry_at=now() + (? * interval '1 second'), error=?, updated_at=now() WHERE event_id=?",
                backoffSeconds, error, eventId);
    }

    public void markFailed(String eventId, String error) {
        jdbc.update("UPDATE wf_inbox_event SET status='FAILED', error=?, updated_at=now() WHERE event_id=?",
                error, eventId);
    }

    /** 供 correlation 重试作业:领取到期的 WAITING_CORRELATION 事件 id。 */
    public java.util.List<String> dueWaitingCorrelation(int limit) {
        return jdbc.queryForList(
                "SELECT event_id FROM wf_inbox_event WHERE status='WAITING_CORRELATION' "
                        + "AND (next_retry_at IS NULL OR next_retry_at <= now()) ORDER BY next_retry_at LIMIT ?",
                String.class, limit);
    }
}
