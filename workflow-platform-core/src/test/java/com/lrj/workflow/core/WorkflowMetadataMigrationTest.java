package com.lrj.workflow.core;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 1:用真 PostgreSQL 验证 wf_* 迁移从空库应用成功,并坐实两条 Phase 2 dedup 逻辑所依赖的 schema 级保证:
 * ①四元组幂等唯一;②同 businessKey 最多一个 WAITING_USER,而 WAITING_BUSINESS 不阻塞新 cycle。
 *
 * <p>用 Testcontainers 自起 PG。若本机 Docker 环境 Testcontainers 不可发现(如 macOS Docker Desktop socket
 * 路径问题),则整类 <b>跳过</b>(不 fail 构建);等价的真实校验由 {@code deploy/scripts/phase1-migration-smoke.sh}
 * 打运行中的 compose PG 完成。手动创建容器(不用 @Container)以便在不可用时 assumeTrue 优雅跳过。
 */
class WorkflowMetadataMigrationTest {

    static PostgreSQLContainer<?> pg;

    @BeforeAll
    static void startAndMigrate() {
        assumeTrue(dockerAvailable(), "Docker 不可用(Testcontainers 无法发现),跳过——改用 phase1-migration-smoke.sh");
        pg = new PostgreSQLContainer<>("postgres:16");
        pg.start();
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private void insertLink(Connection c, String biz, String idem, String pid, String phase) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wf_process_link(tenant_id,process_definition_key,business_key,idempotency_key,"
                        + "process_instance_id,phase,status) VALUES ('his','hisRxReview',?,?,?,?,'ACTIVE')")) {
            ps.setString(1, biz);
            ps.setString(2, idem);
            ps.setString(3, pid);
            ps.setString(4, phase);
            ps.executeUpdate();
        }
    }

    @Test
    void migrationCreatesAllPlatformTables() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'wf_%' ORDER BY table_name");
             ResultSet rs = ps.executeQuery()) {
            java.util.List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).containsExactlyInAnyOrder(
                    "wf_process_link", "wf_inbox_event", "wf_outbox_event",
                    "wf_task_authz_sync", "wf_deployment_audit", "wf_tenant_config");
        }
    }

    @Test
    void fourTupleIdempotencyIsUnique() throws SQLException {
        try (Connection c = conn()) {
            insertLink(c, "enc-2001", "cycle-1", "pi-a", "WAITING_BUSINESS");
            assertThatThrownBy(() -> insertLink(c, "enc-2001", "cycle-1", "pi-b", "WAITING_BUSINESS"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void atMostOneWaitingUserPerBusinessKeyButWaitingBusinessAllowsNewCycle() throws SQLException {
        try (Connection c = conn()) {
            insertLink(c, "enc-3001", "cycle-1", "pi-1", "WAITING_USER");
            assertThatThrownBy(() -> insertLink(c, "enc-3001", "cycle-2", "pi-2", "WAITING_USER"))
                    .isInstanceOf(SQLException.class);

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wf_process_link SET phase='WAITING_BUSINESS' WHERE business_key='enc-3001' AND idempotency_key='cycle-1'")) {
                ps.executeUpdate();
            }
            insertLink(c, "enc-3001", "cycle-2", "pi-2", "WAITING_USER");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM wf_process_link WHERE business_key='enc-3001'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }
}
