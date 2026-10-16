package com.flowdesk.attachment.application.service.impl;

import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;
import com.flowdesk.attachment.application.service.AttachmentService;
import com.flowdesk.attachment.domain.AttachmentAlreadyBoundException;
import com.flowdesk.attachment.domain.AttachmentNotFoundException;
import com.flowdesk.attachment.infrastructure.AttachmentStorage;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 删除未绑定附件的真实 MySQL 集成测试（2026-10-15 修复批）。
 *
 * <p>补的是 mock 证明不了的三件事：① {@code AttachmentMapper.selectByIdForUpdate} 的
 * {@code FOR UPDATE} 语句在真实 MySQL 上能否执行（原生 SQL 的列别名、锁语义都只有真库能验）；
 * ② 「已绑定」这一条的判定必须建立在真实的 {@code ticket_attachment} 绑定行上——用替身让
 * {@code isReferenced} 返回 {@code true} 不能替代它；③ 删除的事务边界与最终文件删除的真实效果。</p>
 *
 * <p>与既有 IT 约定一致：{@code @ActiveProfiles("test")} + Testcontainers、**不使用测试级
 * {@code @Transactional}**（外层事务会掩盖服务自身的事务边界，{@code FOR UPDATE} 也会失效），
 * 每个用例结束后按 {@code itatt-} / {@code ITATT-} 前缀清理自己造的数据与附件文件。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class AttachmentServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itatt-";
    private static final String TICKET_PREFIX = "ITATT-";
    private static final String CATEGORY_NAME = "ITATT-附件删除用例";
    private static final String EMPLOYEE = "EMPLOYEE";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_attachment_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private AttachmentService attachmentService;

    /** 删除必须真的动到最终存储，因此这里直接读存储驱动的事实，而不是只看数据库。 */
    @Autowired
    private AttachmentStorage attachmentStorage;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（上传者端口仍是真的，读 SecurityContext）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        // 先按 storageKey 删文件：元数据一删就再也找不到 key，残留文件会污染后续跑
        jdbc.queryForList("""
                SELECT attachment.storage_key
                FROM attachment
                JOIN iam_user uploader ON uploader.id = attachment.uploaded_by
                WHERE uploader.username LIKE ?
                """, String.class, USERNAME_PREFIX + "%")
                .forEach(attachmentStorage::deleteFinal);
        jdbc.update("""
                DELETE binding
                FROM ticket_attachment binding
                JOIN attachment ON attachment.id = binding.attachment_id
                JOIN iam_user uploader ON uploader.id = attachment.uploaded_by
                WHERE uploader.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("""
                DELETE record
                FROM ticket_record record
                JOIN ticket ON ticket.id = record.ticket_id
                WHERE ticket.ticket_no LIKE ?
                """, TICKET_PREFIX + "%");
        jdbc.update("""
                DELETE participant
                FROM ticket_participant participant
                JOIN ticket ON ticket.id = participant.ticket_id
                WHERE ticket.ticket_no LIKE ?
                """, TICKET_PREFIX + "%");
        jdbc.update("DELETE FROM ticket WHERE ticket_no LIKE ?", TICKET_PREFIX + "%");
        jdbc.update("DELETE FROM ticket_category WHERE name = ?", CATEGORY_NAME);
        jdbc.update("""
                DELETE attachment
                FROM attachment
                JOIN iam_user uploader ON uploader.id = attachment.uploaded_by
                WHERE uploader.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM iam_user WHERE username LIKE ?", USERNAME_PREFIX + "%");
    }

    @Test
    void deleteRemovesMetadataAndFinalFileOfOwnUnboundAttachment() {
        long ownerId = createUser("itatt-owner", EMPLOYEE);
        authenticateAs(ownerId, "itatt-owner");
        long attachmentId = uploadPng("draft.png");
        String storageKey = storageKeyOf(attachmentId);
        assertThat(attachmentStorage.finalExists(storageKey))
                .as("上传成功后最终存储里应当有这份文件").isTrue();

        attachmentService.delete(attachmentId);

        assertThat(attachmentExists(attachmentId)).as("元数据必须删除").isFalse();
        assertThat(attachmentStorage.finalExists(storageKey)).as("最终文件必须删除").isFalse();
    }

    @Test
    void deleteRefusesAttachmentBoundToATicketRecord() {
        long requesterId = createUser("itatt-requester", EMPLOYEE);
        authenticateAs(requesterId, "itatt-requester");
        long attachmentId = uploadPng("evidence.png");
        String storageKey = storageKeyOf(attachmentId);
        long recordId = insertTicketWithRecord(requesterId);
        jdbc.update("""
                INSERT INTO ticket_attachment (attachment_id, record_id, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(3))
                """, attachmentId, recordId);

        assertThatThrownBy(() -> attachmentService.delete(attachmentId))
                .isInstanceOf(AttachmentAlreadyBoundException.class);

        // 已绑定附件是审计链的一部分：元数据与文件都必须原样保留
        assertThat(attachmentExists(attachmentId)).isTrue();
        assertThat(attachmentStorage.finalExists(storageKey)).isTrue();
    }

    @Test
    void deleteHidesAttachmentsUploadedByOthers() {
        long ownerId = createUser("itatt-owner", EMPLOYEE);
        authenticateAs(ownerId, "itatt-owner");
        long attachmentId = uploadPng("private.png");
        String storageKey = storageKeyOf(attachmentId);

        long intruderId = createUser("itatt-intruder", EMPLOYEE);
        authenticateAs(intruderId, "itatt-intruder");

        // 归属不符与不存在对外同码：能删别人的附件本身就是信息泄露
        assertThatThrownBy(() -> attachmentService.delete(attachmentId))
                .isInstanceOf(AttachmentNotFoundException.class);

        assertThat(attachmentExists(attachmentId)).isTrue();
        assertThat(attachmentStorage.finalExists(storageKey)).isTrue();
    }

    @Test
    void deleteReportsUnknownIdAsNotFound() {
        long userId = createUser("itatt-unknown", EMPLOYEE);
        authenticateAs(userId, "itatt-unknown");

        assertThatThrownBy(() -> attachmentService.delete(999_999_999L))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    // ---------- 造数与断言辅助 ----------

    private long uploadPng(String fileName) {
        List<AttachmentUploadResult> results = attachmentService.upload(new UploadAttachmentCommand(
                List.of(new MockMultipartFile("files", fileName, "image/png", PNG))));
        assertThat(results).hasSize(1);
        return results.get(0).id();
    }

    private String storageKeyOf(long attachmentId) {
        return jdbc.queryForObject(
                "SELECT storage_key FROM attachment WHERE id = ?", String.class, attachmentId);
    }

    private boolean attachmentExists(long attachmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE id = ?", Integer.class, attachmentId);
        return count != null && count > 0;
    }

    private long createUser(String username, String roleCode) {
        jdbc.update("""
                INSERT INTO iam_user (
                    username, display_name, password, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'integration-test-hash', 'ENABLED',
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username);
        long userId = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                SELECT ?, id, NULL, CURRENT_TIMESTAMP(3) FROM iam_role WHERE code = ?
                """, userId, roleCode);
        return userId;
    }

    /** 与 {@code JwtAuthenticationFilter} 一致：权限取自会话快照，由角色授权推导。 */
    private void authenticateAs(long userId, String username) {
        List<SimpleGrantedAuthority> authorities = jdbc.queryForList("""
                SELECT DISTINCT permission.code
                FROM iam_user_role user_role
                JOIN iam_role_permission role_permission
                  ON role_permission.role_id = user_role.role_id
                JOIN iam_permission permission
                  ON permission.id = role_permission.permission_id
                WHERE user_role.user_id = ?
                ORDER BY permission.code
                """, String.class, userId).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, username, "itatt-session"), null, authorities));
    }

    /** 造一张带一条记录的工单，返回该记录的 id——绑定表只指向记录，不指向工单。 */
    private long insertTicketWithRecord(long requesterId) {
        jdbc.update("""
                INSERT INTO ticket_category (name, status, sort_order, created_at, updated_at, version)
                VALUES (?, 'ENABLED', 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, CATEGORY_NAME);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = ?", Long.class, CATEGORY_NAME);
        String ticketNo = TICKET_PREFIX + "0001";
        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', 'PENDING', 1, 0,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketNo, UUID.randomUUID().toString(), requesterId,
                "附件删除用例", "用于验证已绑定附件不可删除", categoryId);
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id, created_at
                ) VALUES (?, 1, 'CREATE', 'USER', ?, CURRENT_TIMESTAMP(3))
                """, ticketId, requesterId);
        return jdbc.queryForObject(
                "SELECT id FROM ticket_record WHERE ticket_id = ? AND sequence_no = 1",
                Long.class, ticketId);
    }
}
