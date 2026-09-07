package com.flowdesk.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据基线验证：对空 MySQL 8.4.11 容器执行公共迁移，校验 schema history、
 * 表结构、外键与索引、RBAC 编码完整性、物理约束，以及 demo 数据的可重复执行。
 * prod 等价场景通过“只扫描 classpath:db/migration”体现：迁移后不允许出现任何用户数据。
 */
@Testcontainers
class DatabaseMigrationIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String ROOT_USERNAME = "root";
    private static final String ROOT_PASSWORD = "testcontainers-root";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withPassword(ROOT_PASSWORD);

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "iam_user", "iam_role", "iam_permission", "iam_user_role", "iam_role_permission",
            "ticket_category", "ticket", "ticket_record", "ticket_attachment",
            "ticket_relation", "ticket_participant");

    private static final Set<String> EXPECTED_INDEXES = Set.of(
            "uk_iam_user_username", "uk_iam_role_code", "uk_iam_permission_code",
            "uk_ticket_category_name", "uk_ticket_no", "uk_ticket_requester_submission",
            "uk_ticket_record_ticket_seq", "uk_ticket_attachment_storage_key",
            "uk_ticket_relation_source_type",
            "idx_iam_user_status", "idx_iam_user_role_role_user", "idx_iam_role_permission_permission_role",
            "idx_ticket_category_status_sort", "idx_ticket_requester_created",
            "idx_ticket_status_priority_created", "idx_ticket_assignee_status_updated",
            "idx_ticket_status_deadline", "idx_ticket_category_status",
            "idx_ticket_record_actor_created", "idx_ticket_attachment_record",
            "idx_ticket_relation_target_type_source", "idx_ticket_participant_user_ticket");

    private static final Set<String> EXPECTED_PERMISSIONS = Set.of(
            "TICKET_CREATE", "TICKET_VIEW_OWN", "TICKET_REQUESTER_ACTION",
            "TICKET_VIEW_QUEUE", "TICKET_CLAIM", "TICKET_VIEW_PARTICIPATED",
            "TICKET_PROCESS", "TICKET_TRANSFER", "TICKET_CLOSE",
            "TICKET_ADMIN_HANDOFF", "USER_MANAGE", "CATEGORY_MANAGE", "DASHBOARD_VIEW");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateEmptyDatabase() {
        var flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        flyway.validate();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true));
    }

    @Test
    void emptyDatabaseMigratesFullyWithSchemaForeignKeyAndIndexBaseline() {
        var history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).extracting(row -> String.valueOf(row.get("version")))
                .containsExactly("1", "2");
        assertThat(history).allSatisfy(row ->
                assertThat(row.get("success")).isEqualTo(Boolean.TRUE));

        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(EXPECTED_TABLES.stream(),
                        java.util.stream.Stream.of("flyway_schema_history"))
                        .collect(Collectors.toSet()));

        var foreignKeys = jdbc.queryForList(
                "SELECT constraint_name, delete_rule FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE()");
        assertThat(foreignKeys).hasSize(21);
        assertThat(foreignKeys).allSatisfy(row ->
                assertThat(row.get("delete_rule")).isEqualTo("NO ACTION"));

        var indexNames = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND index_name IN ("
                        + EXPECTED_INDEXES.stream().map(name -> "?").collect(Collectors.joining(", ")) + ")",
                String.class,
                EXPECTED_INDEXES.toArray());
        assertThat(indexNames).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEXES);
    }

    @Test
    void seededRbacMatchesConfirmedBaselineWithoutUserAccounts() {
        var roles = jdbc.queryForList("SELECT code FROM iam_role ORDER BY id", String.class);
        assertThat(roles).containsExactly("EMPLOYEE", "IT_SUPPORT", "SYSTEM_ADMIN");

        var permissions = jdbc.queryForList("SELECT code FROM iam_permission ORDER BY id", String.class);
        assertThat(permissions).hasSize(13);
        assertThat(permissions).containsExactlyInAnyOrderElementsOf(EXPECTED_PERMISSIONS);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_role_permission", Integer.class))
                .isEqualTo(13);
        assertThat(rolePermissionCount("EMPLOYEE")).isEqualTo(3);
        assertThat(rolePermissionCount("IT_SUPPORT")).isEqualTo(7);
        // 系统管理员角色不包含查看具体工单内容的权限
        assertThat(rolePermissionCount("SYSTEM_ADMIN")).isEqualTo(3);

        // 公共迁移不创建任何用户或默认管理员
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_user", Integer.class)).isZero();
    }

    @Test
    void physicalConstraintsRejectInvalidBusinessFacts() {
        long requesterId = insertUser("constraint.requester");
        long itUserId = insertUser("constraint.it");
        long categoryId = insertCategory();

        insertTicket("FD-20260907-000001", requesterId, categoryId, UUID.randomUUID().toString(),
                "PENDING", null, null);
        // 同一提交人重复使用相同 submission_key 必须被唯一约束拒绝
        assertRejected(() -> insertTicket("FD-20260907-000002", requesterId, categoryId,
                jdbc.queryForObject(
                        "SELECT submission_key FROM ticket WHERE ticket_no = 'FD-20260907-000001'",
                        String.class),
                "PENDING", null, null));

        // 非法状态编码
        assertRejected(() -> insertTicket("FD-20260907-000003", requesterId, categoryId,
                UUID.randomUUID().toString(), "UNKNOWN_STATUS", null, null));
        // 待受理不允许有负责人
        assertRejected(() -> insertTicket("FD-20260907-000004", requesterId, categoryId,
                UUID.randomUUID().toString(), "PENDING", itUserId, null));
        // 处理中必须有负责人
        assertRejected(() -> insertTicket("FD-20260907-000005", requesterId, categoryId,
                UUID.randomUUID().toString(), "PROCESSING", null, null));
        // 负责人不能是提交人
        assertRejected(() -> insertTicket("FD-20260907-000006", requesterId, categoryId,
                UUID.randomUUID().toString(), "PROCESSING", requesterId, null));
        // 非待补充/待确认不允许有截止时间
        assertRejected(() -> insertTicket("FD-20260907-000007", requesterId, categoryId,
                UUID.randomUUID().toString(), "PROCESSING", itUserId, "2026-12-31 00:00:00.000"));
        // 非终态不允许有完成方式
        assertRejected(() -> insertTicketWithCompletion("FD-20260907-000008", requesterId,
                categoryId, UUID.randomUUID().toString(), "PROCESSING", itUserId));
        // 自动关闭原因只能是逾期未补充
        assertRejected(() -> insertClosedTicket("FD-20260907-000009", requesterId,
                categoryId, itUserId, "AUTO_SUPPLEMENT_TIMEOUT", "DUPLICATE"));
    }

    @Test
    void demoSeedRunsOnlyWithDemoLocationAndRepeatsWithoutDuplication() {
        String demoUrl = demoJdbcUrl();
        createDatabase(demoUrl, "flowdesk_demo");

        var demoFlyway = Flyway.configure()
                .dataSource(demoUrl, ROOT_USERNAME, ROOT_PASSWORD)
                .locations("classpath:db/migration", "classpath:db/demo")
                .load();
        demoFlyway.migrate();
        demoFlyway.validate();

        var demoJdbc = new JdbcTemplate(new SingleConnectionDataSource(
                demoUrl, ROOT_USERNAME, ROOT_PASSWORD, true));
        assertThat(userCount(demoJdbc)).isEqualTo(3);

        // 直接重复执行 demo 种子脚本，证明可重复且不重复造数、不覆盖已有数据
        executeScript(demoUrl, ROOT_USERNAME, ROOT_PASSWORD, "db/demo/R__seed_demo_data.sql");

        assertThat(userCount(demoJdbc)).isEqualTo(3);
        assertThat(demoJdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user_role ur JOIN iam_user u ON u.id = ur.user_id "
                        + "WHERE u.username LIKE 'demo.%'", Integer.class)).isEqualTo(3);
        assertThat(demoJdbc.queryForObject("SELECT COUNT(*) FROM ticket_category", Integer.class))
                .isEqualTo(5);
        assertThat(demoJdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user WHERE password_hash LIKE '$argon2id$%'", Integer.class))
                .isEqualTo(3);
    }

    private int rolePermissionCount(String roleCode) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role_permission rp "
                        + "JOIN iam_role r ON r.id = rp.role_id WHERE r.code = ?", Integer.class, roleCode);
    }

    private int userCount(JdbcTemplate template) {
        return template.queryForObject("SELECT COUNT(*) FROM iam_user", Integer.class);
    }

    private long insertUser(String username) {
        jdbc.update("INSERT INTO iam_user (username, display_name, password_hash, status, "
                        + "created_at, updated_at, version) VALUES (?, ?, ?, 'ENABLED', ?, ?, 0)",
                username, username, "test-hash", timestamp(), timestamp());
        return jdbc.queryForObject("SELECT id FROM iam_user WHERE username = ?", Long.class, username);
    }

    private long insertCategory() {
        jdbc.update("INSERT INTO ticket_category (name, status, sort_order, created_at, updated_at, version) "
                        + "VALUES (?, 'ENABLED', 0, ?, ?, 0)",
                "constraint-" + UUID.randomUUID(), timestamp(), timestamp());
        return jdbc.queryForObject(
                "SELECT id FROM ticket_category ORDER BY id DESC LIMIT 1", Long.class);
    }

    private void insertTicket(String ticketNo, long requesterId, long categoryId, String submissionKey,
                              String status, Long assigneeId, String deadlineAt) {
        jdbc.update("INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description, "
                        + "category_id, priority, status, assignee_id, action_deadline_at, record_seq, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', ?, ?, ?, 0, 0, ?, ?)",
                ticketNo, submissionKey, requesterId, "约束验证标题", "约束验证描述",
                categoryId, status, assigneeId, deadlineAt, timestamp(), timestamp());
    }

    private void insertTicketWithCompletion(String ticketNo, long requesterId, long categoryId,
                                            String submissionKey, String status, long assigneeId) {
        jdbc.update("INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description, "
                        + "category_id, priority, status, assignee_id, completion_method, record_seq, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', ?, ?, 'REQUESTER_CONFIRMED', 0, 0, ?, ?)",
                ticketNo, submissionKey, requesterId, "约束验证标题", "约束验证描述",
                categoryId, status, assigneeId, timestamp(), timestamp());
    }

    private void insertClosedTicket(String ticketNo, long requesterId, long categoryId, long assigneeId,
                                    String closeMethod, String closeReason) {
        jdbc.update("INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description, "
                        + "category_id, priority, status, assignee_id, close_method, close_reason, "
                        + "ended_at, record_seq, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', 'CLOSED', ?, ?, ?, ?, 0, 0, ?, ?)",
                ticketNo, UUID.randomUUID().toString(), requesterId, "约束验证标题", "约束验证描述",
                categoryId, assigneeId, closeMethod, closeReason, timestamp(), timestamp());
    }

    private void assertRejected(Runnable insert) {
        assertThatThrownBy(insert::run)
                .satisfies(ex -> assertThat(containsSqlException(ex)).isTrue());
    }

    private boolean containsSqlException(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    private String demoJdbcUrl() {
        String baseUrl = MYSQL.getJdbcUrl();
        String prefix = "jdbc:mysql://";
        String hostAndPort = baseUrl.substring(prefix.length(), baseUrl.indexOf('/', prefix.length()));
        return "jdbc:mysql://" + hostAndPort + "/flowdesk_demo?sslMode=DISABLED&allowPublicKeyRetrieval=true";
    }

    private void createDatabase(String urlWithoutSchema, String schema) {
        String baseUrl = urlWithoutSchema.substring(0, urlWithoutSchema.indexOf('/', "jdbc:mysql://".length()) + 1);
        try (Connection connection = DriverManager.getConnection(
                baseUrl, ROOT_USERNAME, ROOT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法创建 demo 验证数据库", exception);
        }
    }

    private void executeScript(String url, String username, String password, String classpathLocation) {
        String script;
        try {
            script = new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取 demo 脚本", exception);
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String part : script.split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("重复执行 demo 脚本失败", exception);
        }
    }

    private String timestamp() {
        return "2026-01-01 00:00:00.000";
    }
}
