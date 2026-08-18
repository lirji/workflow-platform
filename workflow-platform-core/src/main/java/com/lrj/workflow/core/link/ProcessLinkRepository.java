package com.lrj.workflow.core.link;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * wf_process_link 访问。直接 JdbcTemplate,不抽象 Flowable 引擎(ADR)。
 * dedup 与并发保护依赖 DB 唯一约束:四元组唯一(幂等) + WAITING_USER 偏唯一(同 businessKey 一个活动人工待办)。
 */
@Repository
public class ProcessLinkRepository {

    private final JdbcTemplate jdbc;

    public ProcessLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ProcessLink> MAPPER = (rs, n) -> new ProcessLink(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("process_definition_key"),
            rs.getString("business_key"),
            rs.getString("idempotency_key"),
            rs.getString("process_instance_id"),
            ProcessPhase.valueOf(rs.getString("phase")),
            rs.getString("status"),
            rs.getLong("version"));

    /** 按四元组查(幂等键)。 */
    public Optional<ProcessLink> findByIdempotency(String tenant, String defKey, String bizKey, String idemKey) {
        List<ProcessLink> r = jdbc.query(
                "SELECT * FROM wf_process_link WHERE tenant_id=? AND process_definition_key=? "
                        + "AND business_key=? AND idempotency_key=?",
                MAPPER, tenant, defKey, bizKey, idemKey);
        return r.stream().findFirst();
    }

    public Optional<ProcessLink> findByInstanceId(String processInstanceId) {
        List<ProcessLink> r = jdbc.query(
                "SELECT * FROM wf_process_link WHERE process_instance_id=?", MAPPER, processInstanceId);
        return r.stream().findFirst();
    }

    public Optional<ProcessLink> findByTenantAndInstanceId(String tenant, String processInstanceId) {
        List<ProcessLink> r = jdbc.query(
                "SELECT * FROM wf_process_link WHERE tenant_id=? AND process_instance_id=?",
                MAPPER, tenant, processInstanceId);
        return r.stream().findFirst();
    }

    /** 按 businessKey 找处于某阶段的实例(如 WAITING_BUSINESS 关联 ACK)。 */
    public List<ProcessLink> findByBusinessKeyAndPhase(String tenant, String defKey, String bizKey, ProcessPhase phase) {
        return jdbc.query(
                "SELECT * FROM wf_process_link WHERE tenant_id=? AND process_definition_key=? "
                        + "AND business_key=? AND phase=?",
                MAPPER, tenant, defKey, bizKey, phase.name());
    }

    /** 按 businessKey 找全部实例(任意阶段,最新在前)。供 console 只读查询。 */
    public List<ProcessLink> findByBusinessKey(String tenant, String defKey, String bizKey) {
        return jdbc.query(
                "SELECT * FROM wf_process_link WHERE tenant_id=? AND process_definition_key=? "
                        + "AND business_key=? ORDER BY id DESC",
                MAPPER, tenant, defKey, bizKey);
    }

    /**
     * 插入新 link。违反唯一约束(四元组幂等 / WAITING_USER 偏唯一)会抛
     * {@link org.springframework.dao.DuplicateKeyException},由 application service 区分处理。
     */
    public void insert(String tenant, String defKey, String bizKey, String idemKey,
                       String processInstanceId, ProcessPhase phase, String status) {
        jdbc.update(
                "INSERT INTO wf_process_link(tenant_id,process_definition_key,business_key,idempotency_key,"
                        + "process_instance_id,phase,status) VALUES (?,?,?,?,?,?,?)",
                tenant, defKey, bizKey, idemKey, processInstanceId, phase.name(), status);
    }

    /** 运维查询:按租户 +(可选)定义 key +(可选)阶段 列实例(最新在前,限量)。 */
    public List<ProcessLink> search(String tenant, String defKey, String phase, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wf_process_link WHERE tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (defKey != null && !defKey.isBlank()) {
            sql.append(" AND process_definition_key=?");
            args.add(defKey);
        }
        if (phase != null && !phase.isBlank()) {
            sql.append(" AND phase=?");
            args.add(phase);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 运维强制置阶段(不做乐观锁,admin 终止/干预用)。返回命中行数。 */
    public int markPhase(String processInstanceId, ProcessPhase phase) {
        return jdbc.update(
                "UPDATE wf_process_link SET phase=?, status=?, version=version+1, updated_at=now() "
                        + "WHERE process_instance_id=?",
                phase.name(), statusFor(phase), processInstanceId);
    }

    /** 乐观锁更新阶段。返回是否命中(版本匹配)。 */
    public boolean updatePhase(String processInstanceId, ProcessPhase phase, long expectedVersion) {
        int n = jdbc.update(
                "UPDATE wf_process_link SET phase=?, status=?, version=version+1, updated_at=now() "
                        + "WHERE process_instance_id=? AND version=?",
                phase.name(), statusFor(phase), processInstanceId, expectedVersion);
        return n == 1;
    }

    static String statusFor(ProcessPhase phase) {
        return switch (phase) {
            case COMPLETED, CANCELLED -> "ENDED";
            case INCIDENT -> "ERROR";
            case WAITING_USER, WAITING_BUSINESS -> "ACTIVE";
        };
    }
}
