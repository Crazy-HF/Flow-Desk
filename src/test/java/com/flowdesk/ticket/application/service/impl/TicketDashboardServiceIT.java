package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.result.TicketDashboardAssigneeCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardCategoryCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardPriorityCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardResult;
import com.flowdesk.ticket.application.result.TicketDashboardStatusCountResult;
import com.flowdesk.ticket.application.service.TicketDashboardService;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.mapper.TicketDashboardMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 数据概览的真实 MySQL 集成测试（{@code docs/api-design.md} 8.5）。
 *
 * <p>被测面是写在 {@link TicketDashboardMapper} 注解里的聚合 SQL 与它依赖的事务语义，
 * 替身测试一个字都覆盖不到：</p>
 * <ol>
 *   <li><b>与列表对账</b>：同一范围、同一筛选下 {@code total} 必须等于 {@code GET /fd/v1/tickets}
 *       的 {@code totalElements}——这是"概览复用工单可见范围"唯一可检查的形式；</li>
 *   <li><b>四组对账</b>：状态、优先级、分类、负责人四组各自求和都等于 {@code total}，
 *       零结果时同样成立；</li>
 *   <li><b>历史参与去重</b>：参与范围只认 {@code ticket_participant} 的存在性，同一工单只计一次；</li>
 *   <li><b>停用与改名</b>：分类停用、负责人账号停用或改名都不改变是否计入，展示名取当前行；</li>
 *   <li><b>时间边界</b>：UTC 创建时间双端闭区间；</li>
 *   <li><b>单一快照</b>：另一个连接在事务中途提交新工单时，同一次响应内的总数与四组不分裂；</li>
 *   <li><b>计划检查</b>：在 2000 行上记录三组聚合的 {@code EXPLAIN}，据此判断是否需要新索引。</li>
 * </ol>
 *
 * <p>造数一律走 {@link JdbcTemplate}（需要精确控制状态、负责人、分类状态与时间），
 * 行集按"每个用例专属分类 + 专属账号"隔离；身份按 {@code JwtAuthenticationFilter} 的做法写入
 * {@code SecurityContextHolder}，权限由真实角色授权推导。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class TicketDashboardServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itdb-";
    private static final String CATEGORY_PREFIX = "ITDB-";
    private static final String ROLE_PREFIX = "ITDB_ROLE_";

    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELED = "CANCELED";
    private static final String CLOSED = "CLOSED";

    private static final String HIGH = "HIGH";
    private static final String MEDIUM = "MEDIUM";
    private static final String LOW = "LOW";

    /** 造数基准时刻：UTC，毫秒精度与 DATETIME(3) 对齐。 */
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 3, 2, 8, 0, 0, 0);

    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger(3000);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_ticket_dashboard_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private TicketDashboardService ticketDashboardService;

    /** 只用于"与列表对账"：概览总数必须与同条件列表的 {@code totalElements} 相等。 */
    @Autowired
    private TicketQueryService ticketQueryService;

    /** 快照用例直接验证 Mapper 在单个事务内的读一致性（服务层自己开事务，无法在测试里插入中途写入）。 */
    @Autowired
    private TicketDashboardMapper ticketDashboardMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
                DELETE role_permission
                FROM iam_role_permission role_permission
                JOIN iam_role role ON role.id = role_permission.role_id
                WHERE role.code LIKE ?
                """, ROLE_PREFIX + "%");
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM iam_user WHERE username LIKE ?", USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM iam_role WHERE code LIKE ?", ROLE_PREFIX + "%");
    }

    // ---------- 与列表对账 ----------

    /**
     * 三种范围分别与同条件列表的 {@code totalElements} 对齐。
     *
     * <p>这条用例是"概览没有另造一套可见性"的唯一证据：范围谓词在两条 SQL 里各写一份，
     * 一旦 {@code selectScopedPage} 的范围语义变化而概览没跟上，这里立刻失败。</p>
     */
    @ParameterizedTest(name = "{0} 的总数与同条件列表一致")
    @EnumSource(value = TicketScope.class,
            names = {"PENDING_QUEUE", "ASSIGNED_TO_ME", "PARTICIPATED_BY_ME"})
    void totalAlignsWithTheListTotalForTheSameScope(TicketScope scope) {
        long itId = insertUser("align-it", IT_SUPPORT);
        long otherItId = insertUser("align-other-it", IT_SUPPORT);
        long requesterId = insertUser("align-requester", EMPLOYEE);
        long categoryId = insertCategory("对账");

        insertTicket(requesterId, categoryId, "队列里的", PENDING, null, HIGH, BASE_TIME, BASE_TIME);
        insertTicket(requesterId, categoryId, "队列里的第二张", PENDING, null, LOW,
                BASE_TIME.plusHours(1), BASE_TIME.plusHours(1));
        String mine = insertTicket(requesterId, categoryId, "我在负责", PROCESSING, itId, MEDIUM,
                BASE_TIME.plusHours(2), BASE_TIME.plusHours(2));
        // 领取动作会同时写参与者行，因此"我负责的"这张在参与范围里也成立
        insertParticipant(mine, itId);
        String history = insertTicket(requesterId, categoryId, "我参与过", PROCESSING, otherItId,
                MEDIUM, BASE_TIME.plusHours(3), BASE_TIME.plusHours(3));
        insertParticipant(history, itId);
        insertTicket(requesterId, categoryId, "与我无关", PROCESSING, otherItId, MEDIUM,
                BASE_TIME.plusHours(4), BASE_TIME.plusHours(4));

        authenticateAs(itId);

        long dashboardTotal = ticketDashboardService.overview(dashboardQuery(scope, categoryId)).total();
        long listTotal = ticketQueryService.page(listQuery(scope, categoryId)).totalElements();

        assertThat(dashboardTotal)
                .as("范围 %s 的概览总数必须等于列表总数", scope)
                .isEqualTo(listTotal)
                .isEqualTo(switch (scope) {
                    case PENDING_QUEUE -> 2;
                    case ASSIGNED_TO_ME -> 1;
                    case PARTICIPATED_BY_ME -> 2;
                    default -> throw new IllegalArgumentException("概览不接受范围 " + scope);
                });
    }

    // ---------- 四组对账 ----------

    /** 四组各自求和都等于 {@code total}；换范围、加分类筛选、零结果时恒等式仍然成立。 */
    @ParameterizedTest(name = "{0} 的四组之和等于总数")
    @EnumSource(value = TicketScope.class,
            names = {"PENDING_QUEUE", "ASSIGNED_TO_ME", "PARTICIPATED_BY_ME"})
    void everyGroupSumsToTheTotal(TicketScope scope) {
        long itId = insertUser("sums-it", IT_SUPPORT);
        long otherItId = insertUser("sums-other-it", IT_SUPPORT);
        long requesterId = insertUser("sums-requester", EMPLOYEE);
        long categoryId = insertCategory("对账-分组");
        long otherCategoryId = insertCategory("对账-分组二");

        insertTicket(requesterId, categoryId, "待受理一", PENDING, null, HIGH, BASE_TIME, BASE_TIME);
        insertTicket(requesterId, categoryId, "待受理二", PENDING, null, MEDIUM,
                BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));
        String completed = insertTicket(requesterId, categoryId, "已完成保留负责人", COMPLETED, itId,
                LOW, BASE_TIME.plusMinutes(2), BASE_TIME.plusMinutes(2));
        insertParticipant(completed, itId);
        insertTicket(requesterId, otherCategoryId, "已取消无负责人", CANCELED, null, HIGH,
                BASE_TIME.plusMinutes(3), BASE_TIME.plusMinutes(3));
        String transferredAway = insertTicket(requesterId, otherCategoryId, "转交出去的", CLOSED,
                otherItId, MEDIUM, BASE_TIME.plusMinutes(4), BASE_TIME.plusMinutes(4));
        insertParticipant(transferredAway, itId);

        authenticateAs(itId);

        assertGroupsReconcile(ticketDashboardService.overview(dashboardQuery(scope, categoryId)));

        TicketDashboardQuery narrowed = dashboardQuery(scope, categoryId);
        narrowed.setCategoryId(otherCategoryId);
        assertGroupsReconcile(ticketDashboardService.overview(narrowed));

        TicketDashboardQuery empty = dashboardQuery(scope, categoryId);
        empty.setCategoryId(insertCategory("对账-空"));
        TicketDashboardResult emptyResult = ticketDashboardService.overview(empty);
        assertThat(emptyResult.total()).isZero();
        assertGroupsReconcile(emptyResult);
    }

    /** 恒等式本身：四组求和 == total，且零结果时四组都是空数组而不是缺键。 */
    private void assertGroupsReconcile(TicketDashboardResult result) {
        assertThat(sumStatuses(result.byStatus())).as("状态组之和").isEqualTo(result.total());
        assertThat(sumPriorities(result.byPriority())).as("优先级组之和").isEqualTo(result.total());
        assertThat(sumCategories(result.byCategory())).as("分类组之和").isEqualTo(result.total());
        assertThat(sumAssignees(result.byAssignee())).as("负责人组之和").isEqualTo(result.total());
        if (result.total() == 0) {
            assertThat(result.byStatus()).isEmpty();
            assertThat(result.byPriority()).isEmpty();
            assertThat(result.byCategory()).isEmpty();
            assertThat(result.byAssignee()).isEmpty();
        }
    }

    /**
     * 历史参与去重：参与范围只认参与者表的存在性，同一张工单只计一次。
     *
     * <p>三张工单分别是：既参与又仍负责（两个关系同时成立）、只参与过（已经转交出去）、
     * 当前负责但没有参与记录（例如由管理性交接直接换人）。前两张各计一次，第三张不计——
     * 这正是列表 {@code PARTICIPATED_BY_ME} 的口径。若用 {@code JOIN ticket_participant} 而不是
     * {@code EXISTS}，第一张会因为两个关系同时成立而出现两行。</p>
     */
    @Test
    void countsHistoricalParticipationOncePerTicket() {
        long itId = insertUser("dedup-it", IT_SUPPORT);
        long otherItId = insertUser("dedup-other-it", IT_SUPPORT);
        long requesterId = insertUser("dedup-requester", EMPLOYEE);
        long categoryId = insertCategory("去重");

        String bothRelations = insertTicket(requesterId, categoryId, "既负责也参与", PROCESSING, itId,
                MEDIUM, BASE_TIME, BASE_TIME);
        insertParticipant(bothRelations, itId);
        String participantOnly = insertTicket(requesterId, categoryId, "只参与过", CLOSED, otherItId,
                LOW, BASE_TIME.plusHours(1), BASE_TIME.plusHours(1));
        insertParticipant(participantOnly, itId);
        insertTicket(requesterId, categoryId, "只负责没参与记录", PROCESSING, itId, HIGH,
                BASE_TIME.plusHours(2), BASE_TIME.plusHours(2));

        authenticateAs(itId);

        TicketDashboardResult result =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PARTICIPATED_BY_ME, categoryId));

        assertThat(result.total()).as("两张参与者表命中的工单各计一次").isEqualTo(2);
        assertThat(result.byStatus()).extracting(TicketDashboardStatusCountResult::status)
                .containsExactly("PROCESSING", "CLOSED");
        assertThat(sumStatuses(result.byStatus())).isEqualTo(2);
    }

    // ---------- 负责人与分类的边界 ----------

    /**
     * 三类负责人形态都要出现：待受理无负责人、已取消无负责人、终态保留最后负责人。
     *
     * <p>未分配组是唯一允许没有 {@code assignee} 的分组。「我负责的」范围按定义只有本人，
     * 但负责人字段组仍必须返回，不能因为"只有一个值"就省略。</p>
     */
    @Test
    void reportsUnassignedCanceledAndRetainedAssigneeGroups() {
        long itId = insertUser("shape-it", IT_SUPPORT);
        long otherItId = insertUser("shape-other-it", IT_SUPPORT);
        long requesterId = insertUser("shape-requester", EMPLOYEE);
        long categoryId = insertCategory("负责人形态");

        insertTicket(requesterId, categoryId, "待受理", PENDING, null, HIGH, BASE_TIME, BASE_TIME);
        insertTicket(requesterId, categoryId, "已取消", CANCELED, null, MEDIUM,
                BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));
        String completed = insertTicket(requesterId, categoryId, "已完成", COMPLETED, itId, LOW,
                BASE_TIME.plusMinutes(2), BASE_TIME.plusMinutes(2));
        insertParticipant(completed, itId);
        String closed = insertTicket(requesterId, categoryId, "已关闭", CLOSED, itId, LOW,
                BASE_TIME.plusMinutes(3), BASE_TIME.plusMinutes(3));
        insertParticipant(closed, itId);
        insertTicket(requesterId, categoryId, "别人负责的", PROCESSING, otherItId, HIGH,
                BASE_TIME.plusMinutes(4), BASE_TIME.plusMinutes(4));

        authenticateAs(itId);

        TicketDashboardResult queue =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PENDING_QUEUE, categoryId));
        assertThat(queue.total()).isEqualTo(1);
        assertThat(queue.byAssignee()).singleElement().satisfies(row -> {
            assertThat(row.assignee()).as("队列里全部未分配").isNull();
            assertThat(row.count()).isEqualTo(1);
        });

        TicketDashboardResult participated =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PARTICIPATED_BY_ME, categoryId));
        assertThat(participated.total()).isEqualTo(2);
        assertThat(participated.byAssignee()).singleElement().satisfies(row -> {
            assertThat(row.assignee().id()).isEqualTo(itId);
            assertThat(row.count()).as("终态工单保留最后负责人").isEqualTo(2);
        });

        TicketDashboardResult assigned =
                ticketDashboardService.overview(dashboardQuery(TicketScope.ASSIGNED_TO_ME, categoryId));
        assertThat(assigned.total()).as("已完成与已关闭仍算在最后负责人名下").isEqualTo(2);
        assertThat(assigned.byAssignee()).singleElement()
                .satisfies(row -> assertThat(row.assignee().id()).isEqualTo(itId));
    }

    /**
     * 分类停用、分类改名、负责人账号停用或改名，都不改变工单是否计入；展示名取当前关联行。
     *
     * <p>统计对象是当前工单行，不是历史时点快照——所以停用分类的历史工单仍然计入，
     * 且用它的新名字显示。这里的调用者是**另一位**在职 IT：被停用的是"被统计的负责人"，
     * 不是查询者；查询者自己也持有参与关系，因此这些工单在他的参与范围里可见。</p>
     */
    @Test
    void keepsCountingInactiveCategoriesAndDisabledAssigneesWithTheirCurrentNames() {
        long viewerId = insertUser("label-viewer", IT_SUPPORT);
        long disabledItId = insertUser("label-it", IT_SUPPORT);
        long requesterId = insertUser("label-requester", EMPLOYEE);
        long categoryId = insertCategory("改名前的分类");

        String first = insertTicket(requesterId, categoryId, "停用分类的历史工单", PROCESSING,
                disabledItId, MEDIUM, BASE_TIME, BASE_TIME);
        insertParticipant(first, disabledItId);
        insertParticipant(first, viewerId);
        String second = insertTicket(requesterId, categoryId, "第二张", CLOSED, disabledItId, LOW,
                BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));
        insertParticipant(second, disabledItId);
        insertParticipant(second, viewerId);

        jdbc.update("UPDATE ticket_category SET name = ?, status = 'DISABLED' WHERE id = ?",
                CATEGORY_PREFIX + "改名后的分类", categoryId);
        jdbc.update("UPDATE iam_user SET display_name = ?, status = 'DISABLED' WHERE id = ?",
                "停用后的李工", disabledItId);

        authenticateAs(viewerId);

        TicketDashboardResult result =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PARTICIPATED_BY_ME, categoryId));

        assertThat(result.total()).as("停用分类与停用账号都不影响是否计入").isEqualTo(2);
        assertThat(result.byCategory()).singleElement().satisfies(row -> {
            assertThat(row.category().id()).isEqualTo(categoryId);
            assertThat(row.category().name()).isEqualTo(CATEGORY_PREFIX + "改名后的分类");
            assertThat(row.count()).isEqualTo(2);
        });
        assertThat(result.byAssignee()).singleElement()
                .satisfies(row -> assertThat(row.assignee().displayName()).isEqualTo("停用后的李工"));
    }

    // ---------- 时间与分类筛选 ----------

    /** UTC 创建时间双端闭区间：正好落在两端的工单计入，越界的排除。 */
    @Test
    void appliesInclusiveCreationBoundsInUtc() {
        long itId = insertUser("time-it", IT_SUPPORT);
        long requesterId = insertUser("time-requester", EMPLOYEE);
        long categoryId = insertCategory("时间边界");
        LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 3, 2, 0, 0, 0, 0);

        insertTicket(requesterId, categoryId, "起点前一毫秒", PENDING, null, HIGH,
                from.minusNanos(1_000_000), from);
        insertTicket(requesterId, categoryId, "正好在起点", PENDING, null, HIGH, from, from);
        insertTicket(requesterId, categoryId, "区间内", PENDING, null, MEDIUM,
                from.plusHours(12), from.plusHours(12));
        insertTicket(requesterId, categoryId, "正好在终点", PENDING, null, LOW, to, to);
        insertTicket(requesterId, categoryId, "终点后一毫秒", PENDING, null, LOW,
                to.plusNanos(1_000_000), to.plusNanos(1_000_000));

        authenticateAs(itId);

        TicketDashboardQuery query = dashboardQuery(TicketScope.PENDING_QUEUE, categoryId);
        query.setCreatedFrom(from.atOffset(ZoneOffset.UTC));
        query.setCreatedTo(to.atOffset(ZoneOffset.UTC));

        TicketDashboardResult result = ticketDashboardService.overview(query);

        assertThat(result.total()).as("两端都含，越界的两张排除").isEqualTo(3);
        assertThat(result.byPriority()).extracting(TicketDashboardPriorityCountResult::priority)
                .containsExactly(HIGH, MEDIUM, LOW);
        assertGroupsReconcile(result);
    }

    /** 分类筛选生效；合法但没有工单的分类返回零统计，而不是 404。 */
    @Test
    void filtersByCategoryAndReturnsEmptyAggregationForAnUnusedCategory() {
        long itId = insertUser("filter-it", IT_SUPPORT);
        long requesterId = insertUser("filter-requester", EMPLOYEE);
        long usedCategoryId = insertCategory("筛选-在用");
        long otherCategoryId = insertCategory("筛选-另一个");
        long unusedCategoryId = insertCategory("筛选-未用");

        insertTicket(requesterId, usedCategoryId, "命中筛选", PENDING, null, HIGH, BASE_TIME, BASE_TIME);
        insertTicket(requesterId, otherCategoryId, "不命中筛选", PENDING, null, LOW,
                BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));

        authenticateAs(itId);

        assertThat(ticketDashboardService.overview(
                dashboardQuery(TicketScope.PENDING_QUEUE, usedCategoryId)).total()).isEqualTo(1);
        assertThat(ticketDashboardService.overview(
                dashboardQuery(TicketScope.PENDING_QUEUE, otherCategoryId)).total()).isEqualTo(1);

        TicketDashboardResult unused =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PENDING_QUEUE, unusedCategoryId));
        assertThat(unused.total()).isZero();
        assertThat(unused.byStatus()).isEmpty();
        assertThat(unused.byPriority()).isEmpty();
        assertThat(unused.byCategory()).isEmpty();
        assertThat(unused.byAssignee()).isEmpty();
    }

    // ---------- 排序 ----------

    /** 状态按状态机顺序、优先级按高→中→低。 */
    @Test
    void ordersStatusAndPriorityInTheFixedDomainOrder() {
        long itId = insertUser("order-it", IT_SUPPORT);
        long requesterId = insertUser("order-requester", EMPLOYEE);
        long categoryId = insertCategory("排序");

        String closed = insertTicket(requesterId, categoryId, "关闭", CLOSED, itId, LOW,
                BASE_TIME, BASE_TIME);
        insertParticipant(closed, itId);
        insertTicket(requesterId, categoryId, "取消", CANCELED, null, LOW,
                BASE_TIME.plusMinutes(1), BASE_TIME.plusMinutes(1));
        String completed = insertTicket(requesterId, categoryId, "完成", COMPLETED, itId, LOW,
                BASE_TIME.plusMinutes(2), BASE_TIME.plusMinutes(2));
        insertParticipant(completed, itId);
        String confirming = insertTicket(requesterId, categoryId, "待确认", WAITING_FOR_CONFIRMATION,
                itId, HIGH, BASE_TIME.plusMinutes(3), BASE_TIME.plusMinutes(3));
        insertParticipant(confirming, itId);
        String supplementing = insertTicket(requesterId, categoryId, "待补充", WAITING_FOR_REQUESTER,
                itId, HIGH, BASE_TIME.plusMinutes(4), BASE_TIME.plusMinutes(4));
        insertParticipant(supplementing, itId);
        String processing = insertTicket(requesterId, categoryId, "处理中", PROCESSING, itId, MEDIUM,
                BASE_TIME.plusMinutes(5), BASE_TIME.plusMinutes(5));
        insertParticipant(processing, itId);

        authenticateAs(itId);

        TicketDashboardResult result =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PARTICIPATED_BY_ME, categoryId));

        assertThat(result.byStatus()).extracting(TicketDashboardStatusCountResult::status)
                .containsExactly(PROCESSING, WAITING_FOR_REQUESTER, WAITING_FOR_CONFIRMATION,
                        COMPLETED, CLOSED);
        assertThat(result.byPriority()).extracting(TicketDashboardPriorityCountResult::priority)
                .containsExactly(HIGH, MEDIUM, LOW);
    }

    /**
     * 分类按数量降序、再按 ID 升序；负责人同样如此，且空值在同数量时排在有 ID 之后。
     *
     * <p>两组各两张形成并列：分类 A（ID 小）与分类 B 各 2 张；负责人「本人」与「未分配」各 2 张。</p>
     */
    @Test
    void ordersCategoriesAndAssigneesByCountThenIdWithUnassignedLast() {
        long itId = insertUser("tie-it", IT_SUPPORT);
        long requesterId = insertUser("tie-requester", EMPLOYEE);
        long firstCategoryId = insertCategory("并列-A");
        long secondCategoryId = insertCategory("并列-B");

        for (int index = 0; index < 2; index++) {
            String inFirst = insertTicket(requesterId, firstCategoryId, "A" + index, PROCESSING, itId,
                    HIGH, BASE_TIME.plusMinutes(index), BASE_TIME.plusMinutes(index));
            insertParticipant(inFirst, itId);
            String inSecond = insertTicket(requesterId, secondCategoryId, "B" + index, CANCELED, null,
                    HIGH, BASE_TIME.plusMinutes(10L + index), BASE_TIME.plusMinutes(10L + index));
            insertParticipant(inSecond, itId);
        }

        authenticateAs(itId);

        // 不按分类过滤：两组分类都要出现在同一个响应里，才能验证"数量并列时 ID 小的在前"
        TicketDashboardResult result =
                ticketDashboardService.overview(dashboardQuery(TicketScope.PARTICIPATED_BY_ME));

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.byCategory()).extracting(TicketDashboardCategoryCountResult::category)
                .extracting("id")
                .as("两个分类各 2 张，ID 小的在前")
                .containsExactly(firstCategoryId, secondCategoryId);
        assertThat(result.byAssignee()).hasSize(2);
        assertThat(result.byAssignee().get(0).assignee().id()).isEqualTo(itId);
        assertThat(result.byAssignee().get(0).count()).isEqualTo(2);
        assertThat(result.byAssignee().get(1).assignee())
                .as("同数量时未分配组排在后面")
                .isNull();
    }

    // ---------- 授权（真实角色数据） ----------

    /**
     * 多角色管理员：同时持有 {@code SYSTEM_ADMIN} 与一个自建角色（含 {@code DASHBOARD_VIEW}、
     * {@code TICKET_VIEW_QUEUE}、{@code TICKET_VIEW_PARTICIPATED}），但没有 {@code IT_SUPPORT}
     * → {@code 403}。IT 支持人员在同一数据上返回 200，证明拒绝的原因确实是身份而不是数据。
     *
     * <p>这一格是 8.5 的明确边界：权限码可以由任意角色授予，概览仍要求 IT 身份；
     * 管理员角色本身不扩大工单集合。</p>
     */
    @Test
    void multiRoleAdminWithoutItSupportIsRejectedWhileItSupportSucceeds() {
        long adminId = insertUser("multi-role-admin", SYSTEM_ADMIN);
        long grantRoleId = insertRole("DASHBOARD_ONLY",
                "DASHBOARD_VIEW", "TICKET_VIEW_QUEUE", "TICKET_VIEW_PARTICIPATED");
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(3))
                """, adminId, grantRoleId);

        long itId = insertUser("multi-role-it", IT_SUPPORT);
        long requesterId = insertUser("multi-role-requester", EMPLOYEE);
        long categoryId = insertCategory("多角色");
        insertTicket(requesterId, categoryId, "队列工单", PENDING, null, HIGH, BASE_TIME, BASE_TIME);

        authenticateAs(adminId);
        assertAccessDenied(() -> ticketDashboardService.overview(
                dashboardQuery(TicketScope.PENDING_QUEUE, categoryId)));

        authenticateAs(itId);
        assertThat(ticketDashboardService.overview(
                dashboardQuery(TicketScope.PENDING_QUEUE, categoryId)).total()).isEqualTo(1);
    }

    /** 停用账号不再具有 IT 身份：即使会话快照里仍有权限码，概览也拒绝。 */
    @Test
    void disabledItUserIsRejectedEvenWithAStalePermissionSnapshot() {
        long itId = insertUser("disabled-it", IT_SUPPORT);
        long requesterId = insertUser("disabled-requester", EMPLOYEE);
        long categoryId = insertCategory("停用账号");
        insertTicket(requesterId, categoryId, "队列工单", PENDING, null, HIGH, BASE_TIME, BASE_TIME);

        authenticateAs(itId);
        jdbc.update("UPDATE iam_user SET status = 'DISABLED' WHERE id = ?", itId);

        assertAccessDenied(() -> ticketDashboardService.overview(
                dashboardQuery(TicketScope.PENDING_QUEUE, categoryId)));
    }

    // ---------- 单一快照 ----------

    /**
     * 同一次响应内部使用同一个一致性快照：另一个连接在事务中途提交新工单，事务内的总数与四组都不变。
     *
     * <p>服务层的五个查询共用 {@code @Transactional(readOnly = true)} 的同一个事务，
     * MySQL 默认的 {@code REPEATABLE READ} 在第一次一致性读时建立快照。这里直接在事务模板里
     * 复现那条路径：先读总数（建立快照）→ 另一个连接插入并提交 → 再读总数与四组。
     * 若快照没有生效，总数会从 1 变成 2、四组之和也会随之分裂。</p>
     */
    @Test
    void keepsOneConsistentSnapshotWhileAnotherConnectionCommits() throws Exception {
        long itId = insertUser("snapshot-it", IT_SUPPORT);
        long requesterId = insertUser("snapshot-requester", EMPLOYEE);
        long categoryId = insertCategory("快照");
        insertTicket(requesterId, categoryId, "快照内的工单", PENDING, null, HIGH, BASE_TIME, BASE_TIME);

        TicketDashboardQuery query = dashboardQuery(TicketScope.PENDING_QUEUE, categoryId);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        Map<String, Long> observed = template.execute(status -> {
            long before = ticketDashboardMapper.countScoped(itId, query, null, null);

            // 另一个连接：插入一张同分类的待受理工单并提交
            try (Connection external = DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                external.setAutoCommit(true);
                try (PreparedStatement statement = external.prepareStatement("""
                        INSERT INTO ticket (
                            ticket_no, submission_key, requester_id, title, description,
                            category_id, priority, status, assignee_id, action_deadline_at,
                            completion_method, ended_at, record_seq, version, created_at, updated_at
                        ) VALUES (?, ?, ?, '并发插入', '并发插入-描述', ?, 'LOW', 'PENDING', NULL, NULL,
                                  NULL, NULL, 0, 0, ?, ?)
                        """)) {
                    statement.setString(1, "FD-20260302-" + FIXTURE_SEQUENCE.incrementAndGet());
                    statement.setString(2, UUID.randomUUID().toString());
                    statement.setLong(3, requesterId);
                    statement.setLong(4, categoryId);
                    statement.setObject(5, BASE_TIME.plusHours(1));
                    statement.setObject(6, BASE_TIME.plusHours(1));
                    statement.executeUpdate();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("并发写入失败", exception);
            }

            long after = ticketDashboardMapper.countScoped(itId, query, null, null);
            long statusSum = ticketDashboardMapper.countByStatus(itId, query, null, null).stream()
                    .mapToLong(row -> row.getTotal() == null ? 0 : row.getTotal()).sum();

            return Map.of("before", before, "after", after, "statusSum", statusSum);
        });

        assertThat(observed.get("before")).isEqualTo(1);
        assertThat(observed.get("after")).as("事务内第二次读仍是同一快照").isEqualTo(1);
        assertThat(observed.get("statusSum")).isEqualTo(1);

        // 事务结束后新连接的数据可见：证明上面的"没变"是快照，而不是写入没生效
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE category_id = ?", Long.class, categoryId))
                .isEqualTo(2);
    }

    // ---------- EXPLAIN ----------

    /**
     * 在 2000 行上记录三组聚合的执行计划，并断言两个有选择性的范围确实走了索引。
     *
     * <p>依据见 {@code docs/database-design.md} 第 23 节：队列谓词只有 {@code status}，以
     * {@code status} 开头的两条索引（{@code idx_ticket_status_priority_created} /
     * {@code idx_ticket_status_deadline}）都能收敛；当前负责人走
     * {@code idx_ticket_assignee_status_updated}；历史参与走
     * {@code idx_ticket_participant_user_ticket}。小数据集上优化器会全表扫描，因此本用例先造
     * 2000 行并 {@code ANALYZE TABLE}，让"是否需要新索引"这个问题有可检查的答案。</p>
     *
     * <p>计划针对范围谓词本身（不含分类筛选）：本用例 2000 行全在同一个分类里，
     * 带上分类条件会让分类索引看起来和全表一样便宜，反而掩盖真正的访问路径。</p>
     */
    @Test
    void recordsExplainPlansOnARepresentativeDataset() {
        long itId = insertUser("explain-it", IT_SUPPORT);
        long otherItId = insertUser("explain-other-it", IT_SUPPORT);
        long requesterId = insertUser("explain-requester", EMPLOYEE);
        long categoryId = insertCategory("计划");

        List<Object[]> batch = new ArrayList<>();
        int total = 2000;
        for (int index = 0; index < total; index++) {
            // 只有 40 张待受理；约 60 张归本人负责，其余挂在另一个 IT 名下
            boolean pending = index % 50 == 0;
            String status = pending ? PENDING : PROCESSING;
            Long assigneeId = pending ? null : (index % 33 == 0 ? itId : otherItId);
            LocalDateTime createdAt = BASE_TIME.plusSeconds(index);
            batch.add(new Object[]{
                    "FD-20260303-" + FIXTURE_SEQUENCE.incrementAndGet(),
                    UUID.randomUUID().toString(),
                    requesterId,
                    "计划用数据 " + index,
                    "计划用数据-描述 " + index,
                    categoryId,
                    index % 3 == 0 ? HIGH : MEDIUM,
                    status,
                    assigneeId,
                    createdAt,
                    createdAt
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, action_deadline_at,
                    completion_method, ended_at, record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, 0, ?, ?)
                """, batch);

        jdbc.execute("ANALYZE TABLE ticket");

        String queuePlan = explain("""
                SELECT t.status, COUNT(*)
                FROM ticket t
                WHERE t.status = 'PENDING'
                GROUP BY t.status
                """);
        String assignedPlan = explain("""
                SELECT t.status, COUNT(*)
                FROM ticket t
                WHERE t.assignee_id = %d
                GROUP BY t.status
                """.formatted(itId));
        String participatedPlan = explain("""
                SELECT t.status, COUNT(*)
                FROM ticket t
                WHERE EXISTS (
                    SELECT 1 FROM ticket_participant p
                    WHERE p.ticket_id = t.id AND p.user_id = %d
                )
                GROUP BY t.status
                """.formatted(itId));

        System.out.println("[dashboard-explain] queue=" + queuePlan);
        System.out.println("[dashboard-explain] assigned=" + assignedPlan);
        System.out.println("[dashboard-explain] participated=" + participatedPlan);

        // 队列范围的谓词只有 status：任何以 status 开头的索引都能收敛，不锁定具体那一条
        // （优化器在 idx_ticket_status_priority_created 与 idx_ticket_status_deadline 之间按代价选）
        assertThat(queuePlan).as("队列范围应按状态索引收敛").contains("idx_ticket_status");
        assertThat(assignedPlan).as("当前负责人范围应按负责人索引收敛")
                .contains("idx_ticket_assignee_status_updated");
        assertThat(participatedPlan).as("参与范围应能用到参与者索引")
                .contains("idx_ticket_participant_user_ticket");
    }

    /** 取 EXPLAIN 每行的关键列：表、访问类型、选中索引与扫描行数。 */
    private String explain(String sql) {
        List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN " + sql);
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> row : rows) {
            summary.append("table=").append(row.get("table"))
                    .append(",type=").append(row.get("type"))
                    .append(",key=").append(row.get("key"))
                    .append(",rows=").append(row.get("rows"))
                    .append("; ");
        }
        return summary.toString();
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
                        new AuthPrincipal(userId, usernameOf(userId), "itdb-session"),
                        null,
                        authorities));
    }

    private void assertAccessDenied(ThrowingCallable invocation) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exception.code()).isEqualTo("ACCESS_DENIED");
    }

    // ---------- 查询辅助 ----------

    private TicketDashboardQuery dashboardQuery(TicketScope scope, long categoryId) {
        TicketDashboardQuery query = dashboardQuery(scope);
        query.setCategoryId(categoryId);
        return query;
    }

    /** 不按分类过滤：用于需要同时看到多个分类分组的用例。 */
    private TicketDashboardQuery dashboardQuery(TicketScope scope) {
        TicketDashboardQuery query = new TicketDashboardQuery();
        query.setScope(scope);
        return query;
    }

    private TicketQuery listQuery(TicketScope scope, long categoryId) {
        TicketQuery query = new TicketQuery();
        query.setScope(scope);
        query.setCategoryId(categoryId);
        query.setPageSize(50);
        return query;
    }

    private static long sumStatuses(List<TicketDashboardStatusCountResult> rows) {
        return rows.stream().mapToLong(TicketDashboardStatusCountResult::count).sum();
    }

    private static long sumPriorities(List<TicketDashboardPriorityCountResult> rows) {
        return rows.stream().mapToLong(TicketDashboardPriorityCountResult::count).sum();
    }

    private static long sumCategories(List<TicketDashboardCategoryCountResult> rows) {
        return rows.stream().mapToLong(TicketDashboardCategoryCountResult::count).sum();
    }

    private static long sumAssignees(List<TicketDashboardAssigneeCountResult> rows) {
        return rows.stream().mapToLong(TicketDashboardAssigneeCountResult::count).sum();
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

    /** 自建角色：只用于"有权限码但没有 IT 身份"的多角色管理员用例。 */
    private long insertRole(String suffix, String... permissionCodes) {
        String code = ROLE_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO iam_role (code, name, description, created_at)
                VALUES (?, ?, '集成测试自建角色', CURRENT_TIMESTAMP(3))
                """, code, code);
        long roleId = jdbc.queryForObject(
                "SELECT id FROM iam_role WHERE code = ?", Long.class, code);
        for (String permissionCode : permissionCodes) {
            jdbc.update("""
                    INSERT INTO iam_role_permission (role_id, permission_id)
                    SELECT ?, id FROM iam_permission WHERE code = ?
                    """, roleId, permissionCode);
        }
        return roleId;
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
        String ticketNo = "FD-20260302-" + FIXTURE_SEQUENCE.incrementAndGet();
        LocalDateTime actionDeadlineAt = WAITING_FOR_CONFIRMATION.equals(status)
                || WAITING_FOR_REQUESTER.equals(status)
                ? createdAt.plusDays(7)
                : null;
        boolean terminal = COMPLETED.equals(status) || CANCELED.equals(status) || CLOSED.equals(status);
        LocalDateTime endedAt = terminal ? updatedAt : null;
        String completionMethod = COMPLETED.equals(status) ? "REQUESTER_CONFIRMED" : null;
        String closeMethod = CLOSED.equals(status) ? "MANUAL" : null;
        String closeReason = CLOSED.equals(status) ? "OUT_OF_SCOPE" : null;

        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, action_deadline_at,
                    completion_method, close_method, close_reason, ended_at,
                    record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
                """, ticketNo, UUID.randomUUID().toString(), requesterId, title, title + "-描述",
                categoryId, priority, status, assigneeId, actionDeadlineAt,
                completionMethod, closeMethod, closeReason, endedAt, createdAt, updatedAt);
        return ticketNo;
    }

    private void insertParticipant(String ticketNo, long userId) {
        jdbc.update("""
                INSERT INTO ticket_participant (
                    ticket_id, user_id, first_assigned_at, last_assigned_at
                ) VALUES (?, ?, ?, ?)
                """, ticketIdOf(ticketNo), userId, BASE_TIME, BASE_TIME);
    }

    private long ticketIdOf(String ticketNo) {
        return jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }
}
