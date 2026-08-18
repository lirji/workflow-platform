package com.lrj.workflow.core.dlq;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * wf_dlq_event 访问。死信落库 + 按状态查询 + 标记已重放。
 */
@Repository
public class DlqEventRepository {

    private final JdbcTemplate jdbc;

    public DlqEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落库一条死信,返回自增 id。 */
    public long save(String originalTopic, String msgKey, String payload, String signature, String errorMessage) {
        Long id = jdbc.queryForObject(
                "INSERT INTO wf_dlq_event(original_topic,msg_key,payload,signature,error_message) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, originalTopic, msgKey, payload, signature, errorMessage);
        return id == null ? -1L : id;
    }

    /** 按状态列出(最近失败在前)。 */
    public List<DlqRecord> findByStatus(String status, int limit) {
        return jdbc.query("SELECT * FROM wf_dlq_event WHERE status=? ORDER BY failed_at DESC LIMIT ?",
                DlqEventRepository::map, status, limit);
    }

    public Optional<DlqRecord> find(long id) {
        return jdbc.query("SELECT * FROM wf_dlq_event WHERE id=?", DlqEventRepository::map, id).stream().findFirst();
    }

    /** 锁定一条仍为 NEW 的记录；调用方必须在同一事务内完成 Kafka ACK 与状态更新。 */
    public Optional<DlqRecord> findNewForUpdate(long id) {
        return jdbc.query("SELECT * FROM wf_dlq_event WHERE id=? AND status='NEW' FOR UPDATE",
                DlqEventRepository::map, id).stream().findFirst();
    }

    /** 标记已重放。 */
    public void markReplayed(long id) {
        jdbc.update("UPDATE wf_dlq_event SET status='REPLAYED', replayed_at=now() WHERE id=?", id);
    }

    private static DlqRecord map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp failed = rs.getTimestamp("failed_at");
        Timestamp replayed = rs.getTimestamp("replayed_at");
        return new DlqRecord(
                rs.getLong("id"),
                rs.getString("original_topic"),
                rs.getString("msg_key"),
                rs.getString("payload"),
                rs.getString("signature"),
                rs.getString("error_message"),
                rs.getString("status"),
                failed == null ? null : failed.getTime(),
                replayed == null ? null : replayed.getTime());
    }
}
