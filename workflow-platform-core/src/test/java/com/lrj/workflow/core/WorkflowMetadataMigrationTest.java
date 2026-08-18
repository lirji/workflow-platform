package com.lrj.workflow.core;

import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
                    "wf_task_authz_sync", "wf_deployment_audit", "wf_tenant_config", "wf_dlq_event");
        }
    }

    @Test
    void inboxHasHaRetryLeaseColumns() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema='public' AND table_name='wf_inbox_event' "
                             + "AND column_name IN ('lease_owner','lease_until') ORDER BY column_name");
             ResultSet rs = ps.executeQuery()) {
            java.util.List<String> columns = new java.util.ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).containsExactly("lease_owner", "lease_until");
        }
    }

    @Test
    void outboxHasFailureDiagnosticColumn() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema='public' AND table_name='wf_outbox_event' "
                             + "AND column_name='last_error'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("last_error");
        }
    }

    @Test
    void dlqHasOriginalSignatureColumnForAuthenticatedReplay() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema='public' AND table_name='wf_dlq_event' "
                             + "AND column_name='signature'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("signature");
        }
    }

    @Test
    void dlqSchemaAcceptsUntrustedKafkaTopicAndKeyLengths() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        DlqEventRepository dlq = new DlqEventRepository(new JdbcTemplate(dataSource));
        String topic = "t".repeat(500);
        String key = "k".repeat(1_000);

        long id = dlq.save(topic, key, "", null, "invalid record");

        assertThat(dlq.find(id).orElseThrow().originalTopic()).isEqualTo(topic);
        assertThat(dlq.find(id).orElseThrow().msgKey()).isEqualTo(key);
    }

    @Test
    void upgradeFromV3RepairsExistingPhaseStatusDrift() throws SQLException {
        String schema = "upgrade_v4_test";
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate();

        try (Connection c = conn();
             PreparedStatement insert = c.prepareStatement(
                     "INSERT INTO " + schema + ".wf_process_link(tenant_id,process_definition_key,"
                             + "business_key,idempotency_key,process_instance_id,phase,status) "
                             + "VALUES ('his','hisRxReview','enc-v4','cycle-v4','pi-v4','COMPLETED','ACTIVE')")) {
            insert.executeUpdate();
        }

        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (Connection c = conn();
             PreparedStatement select = c.prepareStatement(
                     "SELECT status FROM " + schema + ".wf_process_link WHERE process_instance_id='pi-v4'");
             ResultSet rs = select.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ENDED");
        }
    }

    @Test
    void repositoryClaimQueriesExecuteOnPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        OutboxEventRepository outbox = new OutboxEventRepository(jdbc);
        outbox.enqueue("claim-outbox-1", "topic", "key", "event", "{}");
        assertThat(outbox.claimBatch(1, "node-a", 30))
                .extracting(OutboxEventRepository.OutboxRow::eventId)
                .containsExactly("claim-outbox-1");

        InboxEventRepository inbox = new InboxEventRepository(jdbc);
        assertThat(inbox.tryClaim("claim-inbox-1", "topic", "event", "{}")).isTrue();
        inbox.markWaitingCorrelation("claim-inbox-1", 0, "test");
        assertThat(inbox.claimDueWaitingCorrelation(1, "node-a", 30))
                .containsExactly("claim-inbox-1");
    }

    @Test
    void staleOutboxLeaseOwnerCannotOverwriteNewOwnerResult() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OutboxEventRepository outbox = new OutboxEventRepository(jdbc);
        outbox.enqueue("stale-outbox-1", "topic", "key", "event", "{}");

        assertThat(outbox.claimBatch(1, "owner-a", 30))
                .extracting(OutboxEventRepository.OutboxRow::eventId)
                .containsExactly("stale-outbox-1");
        jdbc.update("UPDATE wf_outbox_event SET lease_until=now() - interval '1 second' WHERE event_id=?",
                "stale-outbox-1");
        assertThat(outbox.renewLease("stale-outbox-1", "owner-a", 30)).isFalse();
        assertThat(outbox.markSent("stale-outbox-1", "owner-a")).isFalse();
        assertThat(outbox.reschedule("stale-outbox-1", "owner-a", 5, "late failure")).isFalse();
        assertThat(outbox.markFailed("stale-outbox-1", "owner-a", "late final failure")).isFalse();
        assertThat(outbox.markDeliveryUnknown("stale-outbox-1", "owner-a", "late timeout")).isFalse();
        assertThat(outbox.claimBatch(1, "owner-b", 30))
                .extracting(OutboxEventRepository.OutboxRow::eventId)
                .containsExactly("stale-outbox-1");

        assertThat(outbox.markSent("stale-outbox-1", "owner-b")).isTrue();
        assertThat(outbox.reschedule("stale-outbox-1", "owner-a", 5, "even later failure")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM wf_outbox_event WHERE event_id='stale-outbox-1'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void deliveryUnknownRequiresExplicitManualRequeueAndKeepsEventId() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OutboxEventRepository outbox = new OutboxEventRepository(jdbc);
        outbox.enqueue("unknown-outbox-1", "topic", "key", "event", "{\"tenantId\":\"his\"}");
        assertThat(outbox.claimBatch(1, "owner-unknown", 30))
                .extracting(OutboxEventRepository.OutboxRow::eventId)
                .containsExactly("unknown-outbox-1");
        assertThat(outbox.markDeliveryUnknown("unknown-outbox-1", "owner-unknown", "ack timeout")).isTrue();
        jdbc.update("UPDATE wf_outbox_event SET last_error=repeat('x', 4000) WHERE event_id=?",
                "unknown-outbox-1");

        assertThat(outbox.claimBatch(1, "automatic-owner", 30)).isEmpty();
        assertThat(outbox.requeueDeliveryUnknown("other", "unknown-outbox-1", "cross tenant attempt")).isFalse();
        assertThat(outbox.requeueDeliveryUnknown("his", "unknown-outbox-1", "target inbox confirmed absent")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT last_error FROM wf_outbox_event WHERE event_id='unknown-outbox-1'", String.class))
                .endsWith("manual-requeue: target inbox confirmed absent");
        assertThat(outbox.requeueDeliveryUnknown("his", "unknown-outbox-1", "duplicate operator action")).isFalse();
        assertThat(outbox.claimBatch(1, "manual-owner", 30))
                .extracting(OutboxEventRepository.OutboxRow::eventId)
                .containsExactly("unknown-outbox-1");
    }

    @Test
    void phaseUpdatesKeepStatusConsistent() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProcessLinkRepository links = new ProcessLinkRepository(jdbc);
        links.insert("his", "hisRxReview", "enc-status", "cycle-status", "pi-status",
                ProcessPhase.WAITING_USER, "ACTIVE");

        assertThat(links.updatePhase("pi-status", ProcessPhase.INCIDENT, 0)).isTrue();
        assertThat(links.findByInstanceId("pi-status").orElseThrow().status()).isEqualTo("ERROR");
        assertThat(links.updatePhase("pi-status", ProcessPhase.COMPLETED, 1)).isTrue();
        assertThat(links.findByInstanceId("pi-status").orElseThrow().status()).isEqualTo("ENDED");
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
