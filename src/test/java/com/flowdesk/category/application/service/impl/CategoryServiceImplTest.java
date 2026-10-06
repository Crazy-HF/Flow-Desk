package com.flowdesk.category.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.query.CategoryQuery;
import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.category.domain.TicketCategory;
import com.flowdesk.category.mapper.TicketCategoryMapper;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分类服务的业务规则：下拉选项与启用判定的查询条件、管理端列表的排序白名单与关键词转义、
 * 新建/修改的校验顺序与乐观锁条件、启停的幂等短路，以及删除时"外键引用"与"其他完整性错误"
 * 的分流。
 *
 * <p>契约见 {@code docs/api-design.md} 8.4。这里用 {@link Clock} 固定落库时间，并断言
 * {@code truncateTo(MILLIS)} 的行为——数据库列是毫秒精度，把纳秒写进断言会让用例依赖运行时钟。</p>
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享"分类行"替身，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceImplTest {

    /** 故意带纳秒，用于断言落库前按毫秒截断。 */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW_UTC =
            LocalDateTime.ofInstant(CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 2, 3, 4);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 20, 5, 6, 7);

    private static final long CATEGORY_ID = 3L;
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";
    private static final String NAME = "办公设备";

    @Mock
    private TicketCategoryMapper ticketCategoryMapper;

    private CategoryServiceImpl service;

    /**
     * 构建 {@code LambdaQueryWrapper} 时会立即解析实体元数据，没有真实 MyBatis 上下文时
     * 必须先注册，否则每个用例都会在建 wrapper 时失败。
     */
    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(TicketCategory.class);
    }

    @BeforeEach
    void setUp() {
        service = new CategoryServiceImpl(CLOCK, ticketCategoryMapper);
    }

    // ---------- options ----------

    /** 下拉选项只取启用分类，顺序由 SQL 的 sort_order/id 决定，服务层不得重排。 */
    @Test
    void optionsReturnsOnlyEnabledCategoriesInMapperOrder() {
        when(ticketCategoryMapper.selectList(any())).thenReturn(List.of(
                category(3L, NAME, ENABLED, 10, 0L),
                category(4L, "网络", ENABLED, 20, 0L)));

        List<CategoryOptionResult> options = service.options();

        assertThat(options)
                .as("顺序保持 Mapper 返回次序，服务层不重排")
                .containsExactly(
                        new CategoryOptionResult(3L, NAME),
                        new CategoryOptionResult(4L, "网络"));

        ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> wrapperCaptor = queryWrapperCaptor();
        verify(ticketCategoryMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSelect()).as("下拉选项只取 id 与 name 两列").isEqualTo("id,name");
        assertThat(wrapper.getSqlSegment())
                .as("启用条件必须下推到 SQL，而不是取回全部再过滤")
                .contains("status =");
        assertThat(wrapper.getSqlSegment())
                .as("展示顺序由 SQL 决定：先 sort_order 再 id")
                .contains("sort_order")
                .contains("id");
        assertThat(boundParams(wrapper))
                .as("启用状态作为绑定参数下发，而不是拼进 SQL 字面量")
                .containsValue(ENABLED);
    }

    @Test
    void optionsReturnsEmptyListWhenNoCategoryIsEnabled() {
        when(ticketCategoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.options()).as("没有启用分类时返回空列表而不是 null").isEmpty();
    }

    // ---------- isEnabled ----------

    @Test
    void isEnabledReturnsTrueWhenEnabledCategoryExists() {
        when(ticketCategoryMapper.selectCount(any())).thenReturn(1L);

        assertThat(service.isEnabled(CATEGORY_ID)).isTrue();

        ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> wrapperCaptor = queryWrapperCaptor();
        verify(ticketCategoryMapper).selectCount(wrapperCaptor.capture());
        LambdaQueryWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("同时按 id 与启用状态判定")
                .contains("id =")
                .contains("status =");
        assertThat(boundParams(wrapper))
                .as("id 与启用状态都作为绑定参数下发")
                .containsValue(CATEGORY_ID)
                .containsValue(ENABLED);
    }

    @Test
    void isEnabledReturnsFalseWhenNoCategoryMatches() {
        when(ticketCategoryMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.isEnabled(CATEGORY_ID)).isFalse();
    }

    // ---------- page ----------

    /** 默认排序必须是 sort_order 升序，并始终追加 id 升序保证同排序值下的稳定次序。 */
    @Test
    void pageUsesSortOrderAscendingWithIdTieBreakerByDefault() {
        stubPage(List.of());

        service.page(new CategoryQuery());

        ArgumentCaptor<Page<TicketCategory>> pageCaptor = pageCaptor();
        verify(ticketCategoryMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).as("默认先按 sort_order").isEqualTo("sort_order");
                    assertThat(order.isAsc()).isTrue();
                },
                order -> {
                    assertThat(order.getColumn()).as("最后一定追加 id 作为稳定次序")
                            .isEqualTo("id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    @Test
    void pageAcceptsWhitelistedOrderFieldAndKeepsIdAsTieBreaker() {
        CategoryQuery query = new CategoryQuery();
        query.setOrderBy("status");
        query.setOrderDirection("desc");
        stubPage(List.of());

        service.page(query);

        ArgumentCaptor<Page<TicketCategory>> pageCaptor = pageCaptor();
        verify(ticketCategoryMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("status");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    /** 多字段降序也要保留 id 升序兜底，否则同排序值的分页结果仍会漂移。 */
    @Test
    void pageKeepsIdAscendingForMultiFieldDescendingOrder() {
        CategoryQuery query = new CategoryQuery();
        query.setOrderBy("sort_order,created_at");
        query.setOrderDirection("desc");
        stubPage(List.of());

        service.page(query);

        ArgumentCaptor<Page<TicketCategory>> pageCaptor = pageCaptor();
        verify(ticketCategoryMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("sort_order");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("created_at");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    @Test
    void pageRejectsOrderFieldOutsideWhitelist() {
        CategoryQuery query = new CategoryQuery();
        query.setOrderBy("id");

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    /** 带分隔符/分号的注入尝试同样只是"不在白名单"，必须在拼 SQL 之前就被拒绝。 */
    @Test
    void pageRejectsOrderFieldWithSqlMetacharacters() {
        CategoryQuery query = new CategoryQuery();
        query.setOrderBy("name;drop");

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void pageRejectsUnknownOrderDirection() {
        CategoryQuery query = new CategoryQuery();
        query.setOrderBy("name");
        query.setOrderDirection("sideways");

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void pageEscapesKeywordAndFiltersByStatus() {
        CategoryQuery query = new CategoryQuery();
        query.setKeyword(" 50%_off! ");
        query.setStatus(ENABLED);
        stubPage(List.of());

        service.page(query);

        ArgumentCaptor<Page<TicketCategory>> pageCaptor = pageCaptor();
        ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> wrapperCaptor = queryWrapperCaptor();
        verify(ticketCategoryMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());

        LambdaQueryWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("关键词走字面子串匹配，并显式声明 ! 作为转义符")
                .contains("name LIKE")
                .contains("ESCAPE '!'");
        assertThat(wrapper.getSqlSegment())
                .as("状态筛选下推到 SQL")
                .contains("status =");
        assertThat(boundParams(wrapper))
                .as("去空白后 ! → !!、% → !%、_ → !_，两侧包通配符；状态作为绑定参数")
                .containsValue("%50!%!_off!!%")
                .containsValue(ENABLED);
    }

    @Test
    void pageOmitsKeywordAndStatusConditionsWhenNotRequested() {
        stubPage(List.of());

        service.page(new CategoryQuery());

        ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> wrapperCaptor = queryWrapperCaptor();
        verify(ticketCategoryMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .as("没有筛选条件时 where 段为空，不会退化成全表条件的误写")
                .doesNotContain("name LIKE")
                .doesNotContain("status");
    }

    @Test
    void pageMapsRowsToResultsAndKeepsMapperPageEnvelope() {
        CategoryQuery query = new CategoryQuery();
        query.setPageNo(2);
        query.setPageSize(20);
        stubPage(List.of(category(CATEGORY_ID, NAME, ENABLED, 10, 2L)), 45);

        PageResult<CategoryResult> result = service.page(query);

        assertThat(result.page()).as("页码来自 Mapper 返回的 Page").isEqualTo(2);
        assertThat(result.size()).as("每页条数来自 Mapper 返回的 Page").isEqualTo(20);
        assertThat(result.totalElements()).as("总数来自 Mapper 返回的 Page").isEqualTo(45);
        assertThat(result.totalPages()).as("45 条按每页 20 条应折成 3 页").isEqualTo(3);

        CategoryResult item = result.items().get(0);
        assertThat(item.id()).isEqualTo(CATEGORY_ID);
        assertThat(item.name()).isEqualTo(NAME);
        assertThat(item.status()).isEqualTo(ENABLED);
        assertThat(item.sortOrder()).isEqualTo(10);
        assertThat(item.createdAt()).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(item.updatedAt()).isEqualTo(UPDATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(item.version()).isEqualTo(2L);
    }

    // ---------- create ----------

    @Test
    void createStoresStrippedNameAsEnabledCategoryAtClockTime() {
        when(ticketCategoryMapper.insert(any(TicketCategory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketCategory.class).setId(CATEGORY_ID);
            return 1;
        });
        ArgumentCaptor<TicketCategory> captor = ArgumentCaptor.forClass(TicketCategory.class);

        CategoryResult result = service.create(new CreateCategoryCommand("  办公设备  ", 10));

        verify(ticketCategoryMapper).insert(captor.capture());
        TicketCategory inserted = captor.getValue();
        assertThat(inserted.getName()).as("名称去除首尾空白后落库").isEqualTo(NAME);
        assertThat(inserted.getStatus()).as("新建分类一律启用").isEqualTo(ENABLED);
        assertThat(inserted.getSortOrder()).isEqualTo(10);
        assertThat(inserted.getVersion()).as("新建分类从版本 0 开始").isZero();
        assertThat(inserted.getCreatedAt()).isEqualTo(NOW_UTC);
        assertThat(inserted.getUpdatedAt()).isEqualTo(NOW_UTC);
        assertThat(NOW_UTC).as("固定 Clock 的纳秒必须按毫秒截断")
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 8, 15, 30, 123_000_000));

        assertThat(result.id()).as("自增主键由 insert 回填到实体").isEqualTo(CATEGORY_ID);
        assertThat(result.name()).isEqualTo(NAME);
        assertThat(result.status()).isEqualTo(ENABLED);
        assertThat(result.sortOrder()).isEqualTo(10);
        assertThat(result.version()).isZero();
        assertThat(result.createdAt()).isEqualTo(NOW_UTC.atOffset(ZoneOffset.UTC));
        assertThat(result.updatedAt()).isEqualTo(NOW_UTC.atOffset(ZoneOffset.UTC));
    }

    @Test
    void createRejectsNullName() {
        assertApiException(() -> service.create(new CreateCategoryCommand(null, 10)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void createRejectsNameThatIsBlankAfterStrip() {
        assertApiException(() -> service.create(new CreateCategoryCommand("   ", 10)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void createRejectsNameOverHundredCharacters() {
        assertApiException(() -> service.create(new CreateCategoryCommand("a".repeat(101), 10)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void createRejectsNullSortOrder() {
        assertApiException(() -> service.create(new CreateCategoryCommand(NAME, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void createRejectsNegativeSortOrder() {
        assertApiException(() -> service.create(new CreateCategoryCommand(NAME, -1)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void createFailsWhenInsertAffectsNoRow() {
        when(ticketCategoryMapper.insert(any(TicketCategory.class))).thenReturn(0);

        assertThatThrownBy(() -> service.create(new CreateCategoryCommand(NAME, 10)))
                .as("插入未生效不能当作创建成功返回")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("分类创建失败");
    }

    @Test
    void createReportsNameConflictOnDuplicateKey() {
        doThrow(new DuplicateKeyException("uk_ticket_category_name"))
                .when(ticketCategoryMapper).insert(any(TicketCategory.class));

        assertApiException(() -> service.create(new CreateCategoryCommand(NAME, 10)),
                HttpStatus.CONFLICT, "CATEGORY_NAME_CONFLICT");
    }

    // ---------- update ----------

    @Test
    void updateStoresNewNameSortOrderAndIncrementedVersion() {
        stubExistingCategory(ENABLED, 2L);
        when(ticketCategoryMapper.update(isNull(), any())).thenReturn(1);

        CategoryResult result = service.update(
                CATEGORY_ID, new UpdateCategoryCommand("  新名字  ", 30, 2L));

        ArgumentCaptor<LambdaUpdateWrapper<TicketCategory>> wrapperCaptor = updateWrapperCaptor();
        verify(ticketCategoryMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("条件同时带 id 与旧版本，乐观锁在 SQL 层生效")
                .contains("id =")
                .contains("version =");
        assertThat(boundParams(wrapper))
                .as("旧版本作为绑定参数参与条件")
                .containsValue(CATEGORY_ID)
                .containsValue(2L);

        assertThat(result.name()).as("名称去除首尾空白后落库").isEqualTo("新名字");
        assertThat(result.sortOrder()).isEqualTo(30);
        assertThat(result.status()).as("改名与排序不改状态").isEqualTo(ENABLED);
        assertThat(result.version()).as("成功后版本 = 旧值 + 1").isEqualTo(3L);
        assertThat(result.updatedAt()).isEqualTo(NOW_UTC.atOffset(ZoneOffset.UTC));
        assertThat(result.createdAt()).as("创建时间不随修改变化")
                .isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void updateReportsNotFoundForNonPositiveCategoryId() {
        assertApiException(
                () -> service.update(0L, new UpdateCategoryCommand(NAME, 10, 0L)),
                HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void updateReportsNotFoundWhenCategoryIsMissing() {
        when(ticketCategoryMapper.selectByIdForUpdate(CATEGORY_ID)).thenReturn(null);

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, 0L)),
                HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void updateRejectsNullVersion() {
        stubExistingCategory(ENABLED, 2L);

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void updateRejectsNegativeVersion() {
        stubExistingCategory(ENABLED, 2L);

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, -1L)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void updateReportsConflictWhenExpectedVersionDiffers() {
        stubExistingCategory(ENABLED, 2L);

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, 3L)),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void updateReportsConflictWhenConditionalUpdateAffectsNoRow() {
        stubExistingCategory(ENABLED, 2L);
        when(ticketCategoryMapper.update(isNull(), any())).thenReturn(0);

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, 2L)),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");
    }

    @Test
    void updateReportsNameConflictOnDuplicateKey() {
        stubExistingCategory(ENABLED, 2L);
        doThrow(new DuplicateKeyException("uk_ticket_category_name"))
                .when(ticketCategoryMapper).update(isNull(), any());

        assertApiException(
                () -> service.update(CATEGORY_ID, new UpdateCategoryCommand(NAME, 10, 2L)),
                HttpStatus.CONFLICT, "CATEGORY_NAME_CONFLICT");
    }

    // ---------- enable / disable ----------

    /** 已经是启用状态：幂等成功，不写库也不推进版本，避免无意义地让其他客户端的版本失效。 */
    @Test
    void enableIsIdempotentWhenCategoryIsAlreadyEnabled() {
        stubExistingCategory(ENABLED, 2L);

        CategoryResult result = service.enable(CATEGORY_ID, new CategoryStatusChangeCommand(2L));

        assertThat(result.status()).isEqualTo(ENABLED);
        assertThat(result.version()).as("状态已是目标值，版本不变").isEqualTo(2L);
        assertThat(result.updatedAt()).as("幂等短路不刷新更新时间")
                .isEqualTo(UPDATED_AT.atOffset(ZoneOffset.UTC));
        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void disableIsIdempotentWhenCategoryIsAlreadyDisabled() {
        stubExistingCategory(DISABLED, 5L);

        CategoryResult result = service.disable(CATEGORY_ID, new CategoryStatusChangeCommand(5L));

        assertThat(result.status()).isEqualTo(DISABLED);
        assertThat(result.version()).as("状态已是目标值，版本不变").isEqualTo(5L);
        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void enableChangesStatusWithOldVersionCondition() {
        stubExistingCategory(DISABLED, 2L);
        when(ticketCategoryMapper.update(isNull(), any())).thenReturn(1);

        CategoryResult result = service.enable(CATEGORY_ID, new CategoryStatusChangeCommand(2L));

        ArgumentCaptor<LambdaUpdateWrapper<TicketCategory>> wrapperCaptor = updateWrapperCaptor();
        verify(ticketCategoryMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("条件同时带 id 与旧版本")
                .contains("id =")
                .contains("version =");
        assertThat(boundParams(wrapper))
                .as("旧版本作为绑定参数参与条件")
                .containsValue(CATEGORY_ID)
                .containsValue(2L);

        assertThat(result.status()).isEqualTo(ENABLED);
        assertThat(result.version()).as("成功后版本 = 旧值 + 1").isEqualTo(3L);
        assertThat(result.updatedAt()).isEqualTo(NOW_UTC.atOffset(ZoneOffset.UTC));
        assertThat(result.createdAt()).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void disableChangesStatusWithOldVersionCondition() {
        stubExistingCategory(ENABLED, 7L);
        when(ticketCategoryMapper.update(isNull(), any())).thenReturn(1);

        CategoryResult result = service.disable(CATEGORY_ID, new CategoryStatusChangeCommand(7L));

        ArgumentCaptor<LambdaUpdateWrapper<TicketCategory>> wrapperCaptor = updateWrapperCaptor();
        verify(ticketCategoryMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("条件同时带 id 与旧版本，乐观锁在 SQL 层生效")
                .contains("id =")
                .contains("version =");
        assertThat(boundParams(wrapper))
                .as("旧版本作为绑定参数参与条件")
                .containsValue(CATEGORY_ID)
                .containsValue(7L);

        assertThat(result.status()).isEqualTo(DISABLED);
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.updatedAt()).isEqualTo(NOW_UTC.atOffset(ZoneOffset.UTC));
    }

    @Test
    void enableReportsConflictWhenExpectedVersionDiffers() {
        stubExistingCategory(DISABLED, 2L);

        assertApiException(
                () -> service.enable(CATEGORY_ID, new CategoryStatusChangeCommand(1L)),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    /**
     * 状态已经是目标值、但客户端版本过期：当前实现先判版本，所以返回 409 而不是幂等成功。
     *
     * <p>这里固化的是"快照过期必须先刷新"的口径；若产品口径改为"目标状态已达成即算成功"，
     * 这条用例应当先被改写，而不是悄悄通过。</p>
     */
    @Test
    void enableReportsConflictWhenStatusAlreadyMatchesButVersionIsStale() {
        stubExistingCategory(ENABLED, 2L);

        assertApiException(
                () -> service.enable(CATEGORY_ID, new CategoryStatusChangeCommand(1L)),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");

        verify(ticketCategoryMapper, never()).update(any(), any());
    }

    @Test
    void enableReportsConflictWhenConditionalUpdateAffectsNoRow() {
        stubExistingCategory(DISABLED, 2L);
        when(ticketCategoryMapper.update(isNull(), any())).thenReturn(0);

        assertApiException(
                () -> service.enable(CATEGORY_ID, new CategoryStatusChangeCommand(2L)),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");
    }

    // ---------- delete ----------

    @Test
    void deleteRejectsNegativeVersion() {
        assertApiException(() -> service.delete(CATEGORY_ID, -1L),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void deleteReportsNotFoundForNonPositiveCategoryId() {
        assertApiException(() -> service.delete(0L, 0L),
                HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");

        verifyNoInteractions(ticketCategoryMapper);
    }

    @Test
    void deleteReportsNotFoundWhenCategoryIsMissing() {
        when(ticketCategoryMapper.selectByIdForUpdate(CATEGORY_ID)).thenReturn(null);

        assertApiException(() -> service.delete(CATEGORY_ID, 0L),
                HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");

        verify(ticketCategoryMapper, never()).delete(any());
    }

    @Test
    void deleteReportsConflictWhenVersionDiffers() {
        stubExistingCategory(ENABLED, 2L);

        assertApiException(() -> service.delete(CATEGORY_ID, 1L),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");

        verify(ticketCategoryMapper, never()).delete(any());
    }

    @Test
    void deleteReportsConflictWhenDeleteAffectsNoRow() {
        stubExistingCategory(ENABLED, 2L);
        when(ticketCategoryMapper.delete(any())).thenReturn(0);

        assertApiException(() -> service.delete(CATEGORY_ID, 2L),
                HttpStatus.CONFLICT, "CATEGORY_CONFLICT");
    }

    @Test
    void deleteRemovesCategoryWhenVersionMatches() {
        stubExistingCategory(ENABLED, 2L);
        when(ticketCategoryMapper.delete(any())).thenReturn(1);

        service.delete(CATEGORY_ID, 2L);

        ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> wrapperCaptor = queryWrapperCaptor();
        verify(ticketCategoryMapper).delete(wrapperCaptor.capture());
        LambdaQueryWrapper<TicketCategory> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("删除条件同时带 id 与期望版本")
                .contains("id =")
                .contains("version =");
        assertThat(boundParams(wrapper))
                .as("id 与版本都作为绑定参数下发")
                .containsValue(CATEGORY_ID)
                .containsValue(2L);
    }

    /** MySQL 1451：父行仍被外键引用。 */
    @Test
    void deleteReportsCategoryInUseWhenForeignKeyIsStillReferenced() {
        stubExistingCategory(ENABLED, 2L);
        doThrow(new DataIntegrityViolationException("fk",
                new SQLException("Cannot delete or update a parent row", "23000", 1451)))
                .when(ticketCategoryMapper).delete(any());

        assertApiException(() -> service.delete(CATEGORY_ID, 2L),
                HttpStatus.CONFLICT, "CATEGORY_IN_USE");
    }

    /** 驱动层常把 SQLException 包在自己的异常里，判定必须沿 cause 链向下找。 */
    @Test
    void deleteReportsCategoryInUseWhenForeignKeyErrorIsNestedInCauseChain() {
        stubExistingCategory(ENABLED, 2L);
        doThrow(new DataIntegrityViolationException("outer",
                new IllegalStateException("inner",
                        new SQLException("Cannot delete or update a parent row", "23000", 1451))))
                .when(ticketCategoryMapper).delete(any());

        assertApiException(() -> service.delete(CATEGORY_ID, 2L),
                HttpStatus.CONFLICT, "CATEGORY_IN_USE");
    }

    /** 其他完整性错误（例如 CHECK 约束）不能误报成"分类被引用"。 */
    @Test
    void deleteRethrowsDataIntegrityViolationThatIsNotForeignKeyReference() {
        stubExistingCategory(ENABLED, 2L);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("check",
                new SQLException("Check constraint violated", "HY000", 3819));
        doThrow(failure).when(ticketCategoryMapper).delete(any());

        assertThatThrownBy(() -> service.delete(CATEGORY_ID, 2L))
                .as("非外键完整性错误必须原样抛出")
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(failure);
    }

    @Test
    void deleteRethrowsDataIntegrityViolationWithoutSqlExceptionCause() {
        stubExistingCategory(ENABLED, 2L);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("no sql cause");
        doThrow(failure).when(ticketCategoryMapper).delete(any());

        assertThatThrownBy(() -> service.delete(CATEGORY_ID, 2L))
                .as("cause 链上没有 SQLException 时不能凭异常类型判定为外键引用")
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(failure);
    }

    // ---------- 事务边界 ----------

    @Test
    void writeMethodsDeclareTransactionsAndPageIsReadOnly() throws NoSuchMethodException {
        assertTransactional("create", CreateCategoryCommand.class);
        assertTransactional("update", long.class, UpdateCategoryCommand.class);
        assertTransactional("enable", long.class, CategoryStatusChangeCommand.class);
        assertTransactional("disable", long.class, CategoryStatusChangeCommand.class);
        assertTransactional("delete", long.class, long.class);

        Transactional page = CategoryServiceImpl.class
                .getMethod("page", CategoryQuery.class)
                .getAnnotation(Transactional.class);
        assertThat(page).as("page 必须声明事务").isNotNull();
        assertThat(page.readOnly()).as("page 只读，不写库").isTrue();

        Transactional create = CategoryServiceImpl.class
                .getMethod("create", CreateCategoryCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(create.readOnly()).as("写方法不能是只读事务").isFalse();
    }

    // ---------- 辅助 ----------

    private static void assertTransactional(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transactional = CategoryServiceImpl.class
                .getMethod(name, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).as("%s 必须声明事务", name).isNotNull();
    }

    private void stubExistingCategory(String status, long version) {
        when(ticketCategoryMapper.selectByIdForUpdate(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, NAME, status, 10, version));
    }

    /** 分页替身：把传入的 Page 当作 MyBatis-Plus 分页插件回填后的结果返回。 */
    private void stubPage(List<TicketCategory> rows, long total) {
        doAnswer(invocation -> {
            Page<TicketCategory> page = invocation.getArgument(0);
            page.setRecords(rows);
            page.setTotal(total);
            return page;
        }).when(ticketCategoryMapper).selectPage(any(), any());
    }

    /**
     * 读取条件包装器绑定的参数值。
     *
     * <p>MyBatis-Plus 的条件参数是随 SQL 片段惰性写入的（只有 {@code set(...)} 的值即时写入），
     * 所以必须先取一次 where 片段再读参数表，否则会看到"条件参数不存在"的假象。</p>
     */
    private static Map<String, Object> boundParams(
            AbstractWrapper<TicketCategory, ?, ?> wrapper) {
        assertThat(wrapper.getSqlSegment()).as("where 片段可以先于参数表求值").isNotNull();
        return wrapper.getParamNameValuePairs();
    }

    private void stubPage(List<TicketCategory> rows) {
        stubPage(rows, rows.size());
    }

    private static TicketCategory category(
            long id, String name, String status, int sortOrder, long version) {
        TicketCategory category = new TicketCategory();
        category.setId(id);
        category.setName(name);
        category.setStatus(status);
        category.setSortOrder(sortOrder);
        category.setCreatedAt(CREATED_AT);
        category.setUpdatedAt(UPDATED_AT);
        category.setVersion(version);
        return category;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Page<TicketCategory>> pageCaptor() {
        return ArgumentCaptor.forClass((Class) Page.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<LambdaQueryWrapper<TicketCategory>> queryWrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<LambdaUpdateWrapper<TicketCategory>> updateWrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private static ApiException assertApiException(
            ThrowingCallable action, HttpStatus status, String code) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("应抛出 ApiException，实际为 %s", thrown)
                .isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.status()).as("HTTP 状态").isEqualTo(status);
        assertThat(exception.code()).as("稳定错误编码").isEqualTo(code);
        return exception;
    }
}
