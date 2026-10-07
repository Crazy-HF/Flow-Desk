package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.domain.TicketListSort;
import com.flowdesk.ticket.domain.TicketPriority;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.domain.TicketStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 工单查询服务的真实 MySQL 集成测试。
 *
 * <p>这一片的被测面是全部写在注解里的 SQL，替身测试一个字都覆盖不到：</p>
 * <ol>
 *   <li><b>四个数据范围</b>：{@code REQUESTED_BY_ME} / {@code PENDING_QUEUE} /
 *       {@code ASSIGNED_TO_ME} / {@code PARTICIPATED_BY_ME} 的 {@code <choose>} 分支，
 *       其中参与范围依赖 {@code ticket_participant} 的 {@code EXISTS}；
 *   <li><b>筛选与排序</b>：多值 {@code IN}、{@code LIKE ... ESCAPE '!'} 的字面匹配、
 *       {@code created_from/created_to} 的 UTC 边界，以及四个 {@code ORDER BY} 分支与
 *       {@code id} 兜底次序；
 *   <li><b>可见性矩阵</b>：详情与时间线共用的 {@code selectVisibleDetail} 在三项权限与
 *       三种资源关系下的组合，无权与不存在统一 {@code 404/TICKET_NOT_FOUND}；
 *   <li><b>{@code allowedActions}</b>：按真实行状态与权限算出的动作提示，
 *       其中"处理中的本人负责人必须能提交解决结果"是阶段 3 修复过的回归点。</li>
 * </ol>
 *
 * <p>造数一律走 {@link JdbcTemplate}：{@code created_at} / {@code updated_at} /
 * {@code priority} / {@code status} 需要精确控制，走服务反而会被"必须存在的前置动作"绑住。
 * 行集按"每个用例一个专属分类 + 专属账号"隔离，避免同一容器里其它用例的残留行影响
 * {@code totalElements} 这类整体断言。身份按 {@code JwtAuthenticationFilter} 的做法写入
 * {@code SecurityContextHolder}，权限由真实角色授权推导 —— 纯管理员之所以看不到工单正文，
 * 依据就是 {@code V2__seed_rbac.sql} 里 {@code SYSTEM_ADMIN} 不含任何 {@code TICKET_VIEW_*}。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class TicketQueryServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itqr-";
    private static final String CATEGORY_PREFIX = "ITQR-";

    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";

    private static final String MEDIUM = "MEDIUM";
    private static final String HIGH = "HIGH";
    private static final String LOW = "LOW";

    /** 造数基准时刻：UTC，毫秒精度与 DATETIME(3) 对齐。 */
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 15, 8, 0, 0, 0);

    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger(900);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_ticket_query_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private TicketQueryService ticketQueryService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（adapter 仍是真的）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE participant
                FROM ticket_participant participant
                JOIN ticket ON ticket.id = participant.ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE record
                FROM ticket_record record
                JOIN ticket ON ticket.id = record.ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE ticket
                FROM ticket
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("DELETE FROM ticket_category WHERE name LIKE ?", CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM iam_user WHERE username LIKE ?", USERNAME_PREFIX + "%");
    }

    // ---------- 四个数据范围 ----------

    @Test
    void fourScopesIsolateRequesterQueueAssigneeAndParticipationHistory() {
        long actorId = insertUser("scope-actor", IT_SUPPORT);
        long otherRequesterId = insertUser("scope-requester", EMPLOYEE);
        long otherItId = insertUser("scope-other-it", IT_SUPPORT);
        long categoryId = insertCategory("范围隔离");

        String requestedByActor = insertTicket(actorId, categoryId,
                "本人提交的待受理", PENDING, null, MEDIUM, BASE_TIME, BASE_TIME);
        String queueOnly = insertTicket(otherRequesterId, categoryId,
                "队列里的待受理", PENDING, null, MEDIUM, BASE_TIME, BASE_TIME);
        String assignedToActor = insertTicket(otherRequesterId, categoryId,
                "当前指派给我", PROCESSING, actorId, MEDIUM, BASE_TIME, BASE_TIME);
        String participatedOnly = insertTicket(otherRequesterId, categoryId,
                "曾经负责过", PROCESSING, otherItId, MEDIUM, BASE_TIME, BASE_TIME);
        insertParticipant(participatedOnly, actorId);
        insertTicket(otherRequesterId, categoryId,
                "与我无关", PROCESSING, otherItId, MEDIUM, BASE_TIME, BASE_TIME);

        authenticateAs(actorId);

        assertThat(ticketNos(page(query(TicketScope.REQUESTED_BY_ME, categoryId))))
                .containsExactly(requestedByActor);
        assertThat(ticketNos(page(query(TicketScope.PENDING_QUEUE, categoryId))))
                .containsExactlyInAnyOrder(requestedByActor, queueOnly);
        assertThat(ticketNos(page(query(TicketScope.ASSIGNED_TO_ME, categoryId))))
                .containsExactly(assignedToActor);
        // 参与历史靠 ticket_participant：不在负责人位置上也能看到，当前负责人身份本身不算参与
        assertThat(ticketNos(page(query(TicketScope.PARTICIPATED_BY_ME, categoryId))))
                .containsExactly(participatedOnly);
    }

    // ---------- 筛选 ----------

    @Test
    void filtersApplyMultiValueStatusPriorityAndLiteralKeyword() {
        long actorId = insertUser("filter-actor", EMPLOYEE);
        long itUserId = insertUser("filter-it", IT_SUPPORT);
        long categoryId = insertCategory("筛选");
        long otherCategoryId = insertCategory("筛选-另一个分类");

        String literalPercent = insertTicket(actorId, categoryId,
                "磁盘 100% 占用", PENDING, null, HIGH, BASE_TIME, BASE_TIME);
        String literalUnderscore = insertTicket(actorId, categoryId,
                "磁盘 100_占用", PENDING, null, LOW, BASE_TIME, BASE_TIME);
        String literalBang = insertTicket(actorId, categoryId,
                "惊叹!告警", PROCESSING, itUserId, MEDIUM, BASE_TIME, BASE_TIME);
        String otherCategory = insertTicket(actorId, otherCategoryId,
                "另一个分类 100% 占用", PENDING, null, HIGH, BASE_TIME, BASE_TIME);

        authenticateAs(actorId);

        TicketQuery singleStatus = query(TicketScope.REQUESTED_BY_ME, categoryId);
        singleStatus.setStatus(List.of(TicketStatus.PENDING));
        assertThat(ticketNos(page(singleStatus)))
                .containsExactlyInAnyOrder(literalPercent, literalUnderscore);

        TicketQuery twoStatuses = query(TicketScope.REQUESTED_BY_ME, categoryId);
        twoStatuses.setStatus(List.of(TicketStatus.PENDING, TicketStatus.PROCESSING));
        assertThat(ticketNos(page(twoStatuses))).hasSize(3).contains(literalBang);

        TicketQuery priorities = query(TicketScope.REQUESTED_BY_ME, categoryId);
        priorities.setPriority(List.of(TicketPriority.HIGH, TicketPriority.LOW));
        assertThat(ticketNos(page(priorities)))
                .containsExactlyInAnyOrder(literalPercent, literalUnderscore);

        // categoryId 把另一个分类的行挡在外面
        assertThat(ticketNos(page(query(TicketScope.REQUESTED_BY_ME, otherCategoryId))))
                .containsExactly(otherCategory);

        // LIKE ... ESCAPE '!'：% 与 _ 是字面量，不是通配符
        assertThat(ticketNos(page(keywordPage(categoryId, "%"))))
                .as("只搜 % 时命中含字面百分号的那一行，而不是全部行")
                .containsExactly(literalPercent);
        assertThat(ticketNos(page(keywordPage(categoryId, "100_"))))
                .containsExactly(literalUnderscore);
        assertThat(ticketNos(page(keywordPage(categoryId, "惊叹!")))).containsExactly(literalBang);
        assertThat(ticketNos(page(keywordPage(categoryId, "100"))))
                .containsExactlyInAnyOrder(literalPercent, literalUnderscore);
        // 关键词同时匹配 ticket_no 与 title 两个分支
        assertThat(ticketNos(page(keywordPage(categoryId, literalBang)))).containsExactly(literalBang);
        assertThat(ticketNos(page(keywordPage(categoryId, "没有任何工单包含这段文字")))).isEmpty();
    }

    @Test
    void utcRangeBoundariesIncludeRowsExactlyOnTheBoundary() {
        long actorId = insertUser("range-actor", EMPLOYEE);
        long categoryId = insertCategory("时间边界");

        LocalDateTime boundary = LocalDateTime.of(2026, 3, 1, 0, 0, 0, 0);
        String onBoundary = insertTicket(actorId, categoryId,
                "边界当天", PENDING, null, MEDIUM, boundary, boundary);
        String nextDay = insertTicket(actorId, categoryId,
                "边界次日", PENDING, null, MEDIUM, boundary.plusDays(1), boundary.plusDays(1));

        authenticateAs(actorId);

        TicketQuery fromBoundary = query(TicketScope.REQUESTED_BY_ME, categoryId);
        fromBoundary.setCreatedFrom(OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        assertThat(ticketNos(page(fromBoundary)))
                .as("created_from 恰好等于某行 created_at 时该行必须包含")
                .containsExactlyInAnyOrder(onBoundary, nextDay);

        TicketQuery toBoundary = query(TicketScope.REQUESTED_BY_ME, categoryId);
        toBoundary.setCreatedTo(OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        assertThat(ticketNos(page(toBoundary)))
                .as("created_to 恰好等于某行 created_at 时该行必须包含")
                .containsExactly(onBoundary);

        TicketQuery afterBoundary = query(TicketScope.REQUESTED_BY_ME, categoryId);
        afterBoundary.setCreatedFrom(OffsetDateTime.parse("2026-03-01T00:00:00.001Z"));
        assertThat(ticketNos(page(afterBoundary)))
                .as("边界后 1 毫秒就不该再包含边界行")
                .containsExactly(nextDay);

        // 非 UTC 偏移按同一时刻换算：+08:00 的 08:00 就是 UTC 的 00:00
        TicketQuery sameInstant = query(TicketScope.REQUESTED_BY_ME, categoryId);
        sameInstant.setCreatedFrom(OffsetDateTime.parse("2026-03-01T08:00:00+08:00"));
        assertThat(ticketNos(page(sameInstant)))
                .as("带偏移的时间必须先换算成 UTC 再比较")
                .containsExactlyInAnyOrder(onBoundary, nextDay);
    }

    // ---------- 排序 ----------

    @Test
    void sortsByCreatedUpdatedAndPriorityWithStableIdTieBreak() {
        long actorId = insertUser("sort-actor", EMPLOYEE);
        long categoryId = insertCategory("排序");

        LocalDateTime base = LocalDateTime.of(2026, 4, 1, 0, 0, 0, 0);
        String earliest = insertTicket(actorId, categoryId, "最早创建", PENDING, null, LOW,
                base, base.plusDays(4));
        String tieFirst = insertTicket(actorId, categoryId, "同时创建甲", PENDING, null, LOW,
                base.plusDays(1), base.plusDays(1));
        String tieSecond = insertTicket(actorId, categoryId, "同时创建乙", PENDING, null, HIGH,
                base.plusDays(1), base.plusDays(1));
        String tieThird = insertTicket(actorId, categoryId, "同时创建丙", PENDING, null, MEDIUM,
                base.plusDays(1), base.plusDays(1));
        String latest = insertTicket(actorId, categoryId, "最晚创建", PENDING, null, HIGH,
                base.plusDays(2), base.plusDays(3));

        authenticateAs(actorId);

        List<String> createdAsc = ticketNos(page(sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.CREATED_ASC)));
        assertThat(createdAsc)
                .containsExactly(earliest, tieFirst, tieSecond, tieThird, latest);
        assertThat(idsOf(page(sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.CREATED_ASC))).subList(1, 4))
                .as("created_at 相同时按 id 升序")
                .isSorted();

        assertThat(ticketNos(page(sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.CREATED_DESC))))
                .containsExactly(latest, tieThird, tieSecond, tieFirst, earliest);
        assertThat(idsOf(page(sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.CREATED_DESC))).subList(1, 4))
                .as("created_at 相同时按 id 降序")
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 先按优先级再按创建时间：HIGH 两级在前，组内 created_at 升序
        assertThat(ticketNos(page(sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.PRIORITY_DESC_CREATED_ASC))))
                .containsExactly(tieSecond, latest, tieThird, earliest, tieFirst);

        // 默认排序：非队列范围按 updated_at 降序，同值时 id 降序
        assertThat(ticketNos(page(query(TicketScope.REQUESTED_BY_ME, categoryId))))
                .containsExactly(earliest, latest, tieThird, tieSecond, tieFirst);
        // 队列范围的默认排序是优先级，而不是 updated_at
        assertThat(ticketNos(page(query(TicketScope.PENDING_QUEUE, categoryId))))
                .as("PENDING_QUEUE 未指定 sort 时走优先级默认排序")
                .containsExactly(tieSecond, latest, tieThird, earliest, tieFirst);
    }

    // ---------- 分页 ----------

    @Test
    void paginatesLastPageOutOfRangeAndAcrossPagesWithoutGaps() {
        long actorId = insertUser("page-actor", EMPLOYEE);
        long categoryId = insertCategory("分页");

        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expected.add(insertTicket(actorId, categoryId, "分页工单-" + index, PENDING, null, MEDIUM,
                    BASE_TIME.plusMinutes(index), BASE_TIME.plusMinutes(index)));
        }

        authenticateAs(actorId);

        TicketQuery query = sortedPage(TicketScope.REQUESTED_BY_ME, categoryId,
                TicketListSort.CREATED_ASC);
        query.setPageSize(2);

        PageResult<TicketListItemResult> firstPage = page(query);
        assertThat(firstPage.page()).isEqualTo(1);
        assertThat(firstPage.size()).as("page/size 原样透传").isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.items()).hasSize(2);

        query.setPage(2);
        PageResult<TicketListItemResult> secondPage = page(query);
        assertThat(secondPage.page()).isEqualTo(2);
        assertThat(secondPage.items()).hasSize(2);

        query.setPage(3);
        PageResult<TicketListItemResult> lastPage = page(query);
        assertThat(lastPage.items()).as("末页只剩 1 条").hasSize(1);
        assertThat(lastPage.totalPages()).isEqualTo(3);

        query.setPage(4);
        PageResult<TicketListItemResult> outOfRange = page(query);
        assertThat(outOfRange.items()).as("越界页返回空集而不是报错").isEmpty();
        assertThat(outOfRange.totalElements()).isEqualTo(5);
        assertThat(outOfRange.totalPages()).isEqualTo(3);
        assertThat(outOfRange.page()).isEqualTo(4);

        LinkedHashSet<String> acrossPages = new LinkedHashSet<>(ticketNos(firstPage));
        acrossPages.addAll(ticketNos(secondPage));
        acrossPages.addAll(ticketNos(lastPage));
        assertThat(acrossPages).as("跨页不重不漏").hasSize(5).containsExactlyElementsOf(expected);
    }

    // ---------- 详情可见性矩阵 ----------

    @Test
    void detailVisibilityMatrixUnifiesUnauthorizedAndMissingAsNotFound() {
        long requesterId = insertUser("detail-requester", EMPLOYEE);
        long assigneeId = insertUser("detail-assignee", IT_SUPPORT);
        long participantId = insertUser("detail-participant", IT_SUPPORT);
        long queueHolderId = insertUser("detail-queue", IT_SUPPORT);
        long unrelatedId = insertUser("detail-unrelated", EMPLOYEE);
        long zeroRoleId = insertUser("detail-zero-role");
        long adminId = insertUser("detail-admin", SYSTEM_ADMIN);
        long categoryId = insertCategory("详情可见性");

        String pendingNo = insertTicket(requesterId, categoryId,
                "待受理详情", PENDING, null, MEDIUM, BASE_TIME, BASE_TIME);
        String processingNo = insertTicket(requesterId, categoryId,
                "处理中详情", PROCESSING, assigneeId, HIGH, BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));
        insertParticipant(processingNo, participantId);

        // 本人：能读，并且字段映射完整
        authenticateAs(requesterId);
        TicketDetailResult own = ticketQueryService.detail(pendingNo);
        assertThat(own.ticketNo()).isEqualTo(pendingNo);
        assertThat(own.title()).isEqualTo("待受理详情");
        assertThat(own.description()).isEqualTo("待受理详情-描述");
        assertThat(own.category().id()).isEqualTo(categoryId);
        assertThat(own.category().name()).isEqualTo(CATEGORY_PREFIX + "详情可见性");
        assertThat(own.requester().id()).isEqualTo(requesterId);
        assertThat(own.assignee()).isNull();
        assertThat(own.priority()).isEqualTo(MEDIUM);
        assertThat(own.status()).isEqualTo(PENDING);
        assertThat(own.version()).isZero();
        assertThat(own.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(own.completionMethod()).isNull();
        assertThat(own.endedAt()).isNull();
        assertThat(own.allowedActions()).as("提交人自己不能领取自己的工单").isEmpty();

        // 当前负责人
        authenticateAs(assigneeId);
        assertThat(ticketQueryService.detail(processingNo).assignee().id()).isEqualTo(assigneeId);

        // 历史参与者：不是当前负责人，但参与过
        authenticateAs(participantId);
        assertThat(ticketQueryService.detail(processingNo).ticketNo()).isEqualTo(processingNo);

        // 队列权限只看得到待受理，看不到处理中
        authenticateAs(queueHolderId);
        assertThat(ticketQueryService.detail(pendingNo).ticketNo()).isEqualTo(pendingNo);
        assertTicketNotFound(() -> ticketQueryService.detail(processingNo));

        // 无关员工、零角色用户、纯管理员：都统一 404，不确认工单是否存在
        authenticateAs(unrelatedId);
        assertTicketNotFound(() -> ticketQueryService.detail(pendingNo));
        authenticateAs(zeroRoleId);
        assertTicketNotFound(() -> ticketQueryService.detail(pendingNo));
        authenticateAs(adminId);
        assertTicketNotFound(() -> ticketQueryService.detail(pendingNo));
        assertTicketNotFound(() -> ticketQueryService.detail(processingNo));

        // 不存在的编号
        authenticateAs(requesterId);
        assertTicketNotFound(() -> ticketQueryService.detail("FD-19700101-001"));
    }

    @Test
    void detailOfHistoryStaysReadableAfterCategoryIsDisabled() {
        long requesterId = insertUser("disabled-requester", EMPLOYEE);
        long categoryId = insertCategory("停用分类历史");
        String ticketNo = insertTicket(requesterId, categoryId,
                "停用分类的历史工单", PENDING, null, MEDIUM, BASE_TIME, BASE_TIME);

        jdbc.update("""
                UPDATE ticket_category
                SET status = 'DISABLED', version = version + 1, updated_at = ?
                WHERE id = ?
                """, BASE_TIME.plusDays(1), categoryId);

        authenticateAs(requesterId);

        assertThat(categoryStatusOf(categoryId)).isEqualTo("DISABLED");
        assertThat(ticketQueryService.detail(ticketNo).ticketNo())
                .as("停用分类不能挡住历史工单的详情")
                .isEqualTo(ticketNo);
        assertThat(ticketNos(page(query(TicketScope.REQUESTED_BY_ME, categoryId))))
                .as("列表 SQL 也不按分类状态过滤")
                .containsExactly(ticketNo);
    }

    // ---------- 时间线 ----------

    @Test
    void timelineOrdersBySequenceAcrossPagesAndExposesOnlyAllowedContextFields() {
        long requesterId = insertUser("timeline-requester", EMPLOYEE);
        long assigneeId = insertUser("timeline-assignee", IT_SUPPORT);
        long unrelatedId = insertUser("timeline-unrelated", EMPLOYEE);
        long categoryId = insertCategory("时间线");
        String ticketNo = insertTicket(requesterId, categoryId,
                "完整时间线", COMPLETED, assigneeId, HIGH, BASE_TIME, BASE_TIME.plusHours(5));
        long ticketId = ticketIdOf(ticketNo);

        LocalDateTime deadline = BASE_TIME.plusDays(3);
        insertCreateRecord(ticketId, requesterId, categoryId);
        insertClaimRecord(ticketId, assigneeId);
        insertProcessRecord(ticketId, assigneeId, "已联系厂商");
        insertResolutionRecord(ticketId, assigneeId, "已更换网线", deadline);
        insertCompletionRecord(ticketId, requesterId);
        jdbc.update("UPDATE ticket SET record_seq = 5 WHERE id = ?", ticketId);

        authenticateAs(requesterId);

        TicketRecordQuery query = new TicketRecordQuery();
        query.setPageSize(2);

        PageResult<TicketRecordResult> firstPage = records(ticketNo, query);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(TicketRecordResult::sequenceNo).containsExactly(1, 2);

        query.setPage(2);
        PageResult<TicketRecordResult> secondPage = records(ticketNo, query);
        assertThat(secondPage.items()).extracting(TicketRecordResult::sequenceNo).containsExactly(3, 4);

        query.setPage(3);
        PageResult<TicketRecordResult> lastPage = records(ticketNo, query);
        assertThat(lastPage.items()).extracting(TicketRecordResult::sequenceNo).containsExactly(5);

        List<TicketRecordResult> timeline = new ArrayList<>(firstPage.items());
        timeline.addAll(secondPage.items());
        timeline.addAll(lastPage.items());

        assertThat(timeline).extracting(TicketRecordResult::recordType).containsExactly(
                "CREATE", "CLAIM", "PROCESS", "RESOLUTION", "COMPLETION");
        assertThat(timeline).extracting(TicketRecordResult::sequenceNo)
                .as("跨页合计严格升序且无跳号")
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(timeline).extracting(TicketRecordResult::actorType).containsOnly("USER");
        assertThat(timeline.getFirst().actor().displayName())
                .isEqualTo(USERNAME_PREFIX + "timeline-requester");

        // context 只含该记录类型允许的字段，不整表序列化
        assertThat(timeline.get(0).context())
                .containsOnlyKeys("toStatus", "categoryId", "priority")
                .containsEntry("toStatus", PENDING)
                .containsEntry("categoryId", categoryId);
        assertThat(timeline.get(1).context())
                .containsOnlyKeys("assigneeId", "fromStatus", "toStatus")
                .containsEntry("assigneeId", assigneeId)
                .containsEntry("fromStatus", PENDING)
                .containsEntry("toStatus", PROCESSING);
        assertThat(timeline.get(2).context())
                .containsOnlyKeys("content")
                .containsEntry("content", "已联系厂商");
        assertThat(timeline.get(3).context())
                .containsOnlyKeys("content", "deadlineAt", "fromStatus", "toStatus")
                .containsEntry("content", "已更换网线")
                .containsEntry("deadlineAt", deadline.atOffset(ZoneOffset.UTC))
                .containsEntry("fromStatus", PROCESSING)
                .containsEntry("toStatus", WAITING_FOR_CONFIRMATION);
        assertThat(timeline.get(4).context())
                .containsOnlyKeys("completionMethod", "fromStatus", "toStatus")
                .containsEntry("completionMethod", "REQUESTER_CONFIRMED")
                .containsEntry("toStatus", COMPLETED);

        // 与详情同一套可见性：无关用户与不存在的编号都读不到时间线
        authenticateAs(unrelatedId);
        assertTicketNotFound(() -> records(ticketNo, new TicketRecordQuery()));
        assertTicketNotFound(() -> records("FD-19700101-001", new TicketRecordQuery()));
    }

    // ---------- allowedActions ----------

    @Test
    void allowedActionsReflectRealRowStateAndPermissions() {
        long requesterId = insertUser("actions-requester", EMPLOYEE);
        long assigneeId = insertUser("actions-assignee", IT_SUPPORT);
        long queueItId = insertUser("actions-queue-it", IT_SUPPORT);
        long categoryId = insertCategory("动作提示");

        String pendingNo = insertTicket(requesterId, categoryId,
                "待受理动作", PENDING, null, MEDIUM, BASE_TIME, BASE_TIME);
        String processingNo = insertTicket(requesterId, categoryId,
                "处理中动作", PROCESSING, assigneeId, MEDIUM, BASE_TIME, BASE_TIME);
        String waitingNo = insertTicket(requesterId, categoryId,
                "待确认动作", WAITING_FOR_CONFIRMATION, assigneeId, MEDIUM, BASE_TIME, BASE_TIME);
        String completedNo = insertTicket(requesterId, categoryId,
                "已完成动作", COMPLETED, assigneeId, MEDIUM, BASE_TIME, BASE_TIME);

        // 处理中 + 本人负责人：既要能追加处理记录，也要能提交解决结果
        authenticateAs(assigneeId);
        assertThat(ticketQueryService.detail(processingNo).allowedActions())
                .as("阶段 3 回归：负责人必须同时拿到两个动作")
                .containsExactlyInAnyOrder("add-processing-record", "submit-resolution");
        assertThat(ticketQueryService.detail(completedNo).allowedActions())
                .as("终态没有可执行动作")
                .isEmpty();
        assertThat(ticketQueryService.detail(waitingNo).allowedActions())
                .as("负责人不是提交人，不能确认")
                .isEmpty();

        // 待确认 + 提交人：确认与「问题仍未解决」两个动作的前置条件完全相同，
        // 顺序由装配顺序决定（片 A 起从一格变成两格，旧断言只写了 confirm-resolution）
        authenticateAs(requesterId);
        assertThat(ticketQueryService.detail(waitingNo).allowedActions())
                .as("待确认时提交人同时拿到确认与反馈未解决，顺序固定")
                .containsExactly("confirm-resolution", "report-unresolved");
        assertThat(ticketQueryService.detail(processingNo).allowedActions())
                .as("提交人没有处理权限，看不到处理动作")
                .isEmpty();
        assertThat(ticketQueryService.detail(pendingNo).allowedActions())
                .as("不能领取自己提交的工单")
                .isEmpty();

        // 待受理 + 具备队列/领取权限的 IT（非提交人）：只允许领取
        authenticateAs(queueItId);
        assertThat(ticketQueryService.detail(pendingNo).allowedActions())
                .containsExactly("claim");
    }

    // ---------- 查询辅助 ----------

    private TicketQuery query(TicketScope scope, long categoryId) {
        TicketQuery query = new TicketQuery();
        query.setScope(scope);
        query.setCategoryId(categoryId);
        query.setPageSize(50);
        return query;
    }

    private TicketQuery sortedPage(TicketScope scope, long categoryId, TicketListSort sort) {
        TicketQuery query = query(scope, categoryId);
        query.setSort(sort);
        return query;
    }

    private TicketQuery keywordPage(long categoryId, String keyword) {
        TicketQuery query = query(TicketScope.REQUESTED_BY_ME, categoryId);
        query.setKeyword(keyword);
        return query;
    }

    private PageResult<TicketListItemResult> page(TicketQuery query) {
        return ticketQueryService.page(query);
    }

    private PageResult<TicketRecordResult> records(String ticketNo, TicketRecordQuery query) {
        return ticketQueryService.records(ticketNo, query);
    }

    private static List<String> ticketNos(PageResult<TicketListItemResult> page) {
        return page.items().stream().map(TicketListItemResult::ticketNo).toList();
    }

    /** 列表投影不返回内部主键，因此按编号回查 id 来断言排序兜底列。 */
    private List<Long> idsOf(PageResult<TicketListItemResult> page) {
        return page.items().stream().map(item -> ticketIdOf(item.ticketNo())).toList();
    }

    // ---------- 身份与断言辅助 ----------

    /** 与 {@code JwtAuthenticationFilter} 一致：权限来自会话快照，由真实角色授权推导。 */
    private void authenticateAs(long userId) {
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
                        new AuthPrincipal(userId, usernameOf(userId), "itqr-session"),
                        null,
                        authorities));
    }

    private void assertTicketNotFound(ThrowingCallable invocation) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.code())
                .as("无权与不存在必须返回同一个错误码")
                .isEqualTo("TICKET_NOT_FOUND");
    }

    // ---------- 造数辅助 ----------

    private long insertUser(String suffix, String... roleCodes) {
        String username = USERNAME_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO iam_user (
                    username, display_name, password, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'integration-test-hash', 'ENABLED',
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username);
        long userId = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
        for (String roleCode : roleCodes) {
            jdbc.update("""
                    INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                    SELECT ?, id, NULL, CURRENT_TIMESTAMP(3) FROM iam_role WHERE code = ?
                    """, userId, roleCode);
        }
        return userId;
    }

    private long insertCategory(String suffix) {
        String name = CATEGORY_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO ticket_category (
                    name, status, sort_order, created_at, updated_at, version
                ) VALUES (?, 'ENABLED', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, name);
        return jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = ?", Long.class, name);
    }

    /**
     * 直接造一张满足全部 CHECK 约束的工单：截止时间与结束时间由状态推导，
     * 避免为了造一个"待确认"的行而先跑一遍完整状态机。
     */
    private String insertTicket(long requesterId, long categoryId, String title, String status,
                                Long assigneeId, String priority,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
        String ticketNo = "FD-20260202-" + FIXTURE_SEQUENCE.incrementAndGet();
        LocalDateTime actionDeadlineAt =
                WAITING_FOR_CONFIRMATION.equals(status) ? createdAt.plusDays(7) : null;
        LocalDateTime endedAt = COMPLETED.equals(status) ? updatedAt : null;
        String completionMethod = COMPLETED.equals(status) ? "REQUESTER_CONFIRMED" : null;

        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, action_deadline_at,
                    completion_method, ended_at, record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
                """, ticketNo, UUID.randomUUID().toString(), requesterId, title, title + "-描述",
                categoryId, priority, status, assigneeId, actionDeadlineAt, completionMethod,
                endedAt, createdAt, updatedAt);
        return ticketNo;
    }

    private void insertParticipant(String ticketNo, long userId) {
        jdbc.update("""
                INSERT INTO ticket_participant (
                    ticket_id, user_id, first_assigned_at, last_assigned_at
                ) VALUES (?, ?, ?, ?)
                """, ticketIdOf(ticketNo), userId, BASE_TIME, BASE_TIME);
    }

    private void insertCreateRecord(long ticketId, long requesterId, long categoryId) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    to_status, to_category_id, to_priority, created_at
                ) VALUES (?, 1, 'CREATE', 'USER', ?, 'PENDING', ?, 'HIGH', ?)
                """, ticketId, requesterId, categoryId, BASE_TIME);
    }

    private void insertClaimRecord(long ticketId, long assigneeId) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    from_status, to_status, to_assignee_id, created_at
                ) VALUES (?, 2, 'CLAIM', 'USER', ?, 'PENDING', 'PROCESSING', ?, ?)
                """, ticketId, assigneeId, assigneeId, BASE_TIME.plusHours(1));
    }

    private void insertProcessRecord(long ticketId, long assigneeId, String content) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    content, from_status, to_status, created_at
                ) VALUES (?, 3, 'PROCESS', 'USER', ?, ?, 'PROCESSING', 'PROCESSING', ?)
                """, ticketId, assigneeId, content, BASE_TIME.plusHours(2));
    }

    private void insertResolutionRecord(long ticketId, long assigneeId, String content,
                                        LocalDateTime deadlineAt) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    content, from_status, to_status, deadline_at, created_at
                ) VALUES (?, 4, 'RESOLUTION', 'USER', ?, ?, 'PROCESSING',
                          'WAITING_FOR_CONFIRMATION', ?, ?)
                """, ticketId, assigneeId, content, deadlineAt, BASE_TIME.plusHours(3));
    }

    private void insertCompletionRecord(long ticketId, long requesterId) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    completion_method, from_status, to_status, created_at
                ) VALUES (?, 5, 'COMPLETION', 'USER', ?, 'REQUESTER_CONFIRMED',
                          'WAITING_FOR_CONFIRMATION', 'COMPLETED', ?)
                """, ticketId, requesterId, BASE_TIME.plusHours(4));
    }

    // ---------- 直查辅助 ----------

    private long ticketIdOf(String ticketNo) {
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        assertThat(ticketId).as("工单必须存在：" + ticketNo).isNotNull();
        return ticketId;
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }

    private String categoryStatusOf(long categoryId) {
        return jdbc.queryForObject(
                "SELECT status FROM ticket_category WHERE id = ?", String.class, categoryId);
    }
}
