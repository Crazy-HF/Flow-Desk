package com.flowdesk.category.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.query.CategoryQuery;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.support.MockedPersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类管理接口的 Web 契约测试（{@code docs/api-design.md} 8.4）。
 *
 * <p>类级 {@code @PreAuthorize} 要求 {@code CATEGORY_MANAGE}，{@link CategoryService} 替换为替身：
 * 本类只验证 HTTP 状态、响应信封、请求绑定（尤其是删除端点的必填查询参数 {@code version}）、
 * 校验失败与六个端点的权限边界。名称唯一、版本判定、引用约束与排序白名单都在服务实现里，
 * 已由 {@code CategoryServiceImplTest} 覆盖，此处用替身异常验证"服务异常 → HTTP 契约"的映射。</p>
 *
 * <p>六个端点：列表 {@code GET}、创建 {@code POST}（唯一返回 {@code 201}）、
 * 修改 {@code PUT /{categoryId}}、启用/停用 {@code POST /{categoryId}/actions/*}、
 * 删除 {@code DELETE /{categoryId}?version=}（返回 {@code R<Void>}）。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(MockedPersistenceConfiguration.class)
class AdminCategoryControllerWebTest {

    private static final String CATEGORIES = "/fd/v1/admin/categories";
    private static final long CATEGORY_ID = 3L;
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-09-01T02:03:04Z");
    private static final OffsetDateTime UPDATED_AT =
            OffsetDateTime.parse("2026-09-20T05:06:07Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("categoryAdminRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(categoryService);
    }

    /** 工单权限不能替代分类管理权限：员工提交工单的权限与改业务配置是两件事。 */
    @ParameterizedTest(name = "{0} without CATEGORY_MANAGE returns 403")
    @MethodSource("categoryAdminRequests")
    void everyEndpointRequiresCategoryManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(ticketCreator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(categoryService);
    }

    static Stream<Arguments> categoryAdminRequests() {
        return Stream.of(
                Arguments.of("list categories", get(CATEGORIES)),
                Arguments.of("create category", post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody())),
                Arguments.of("update category", put(CATEGORIES + "/" + CATEGORY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody())),
                Arguments.of("enable category",
                        post(CATEGORIES + "/" + CATEGORY_ID + "/actions/enable")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(statusBody())),
                Arguments.of("disable category",
                        post(CATEGORIES + "/" + CATEGORY_ID + "/actions/disable")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(statusBody())),
                Arguments.of("delete category", delete(CATEGORIES + "/" + CATEGORY_ID)
                        .queryParam("version", "2"))
        );
    }

    // ---------- 列表 ----------

    @Test
    void categoryManagerCanListCategoriesAndQueryParametersReachService() throws Exception {
        when(categoryService.page(any())).thenReturn(
                new PageResult<>(List.of(sampleCategory("ENABLED", 2L)), 2, 50, 1, 1));

        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "50")
                        .queryParam("keyword", "办公")
                        .queryParam("status", "ENABLED")
                        .queryParam("orderBy", "name")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(CATEGORY_ID))
                .andExpect(jsonPath("$.data.items[0].name").value("办公设备"))
                .andExpect(jsonPath("$.data.items[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.data.items[0].sortOrder").value(10))
                .andExpect(jsonPath("$.data.items[0].version").value(2));

        ArgumentCaptor<CategoryQuery> captor = ArgumentCaptor.forClass(CategoryQuery.class);
        verify(categoryService).page(captor.capture());
        CategoryQuery query = captor.getValue();
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(50);
        assertThat(query.getKeyword()).isEqualTo("办公");
        assertThat(query.getStatus()).isEqualTo("ENABLED");
        assertThat(query.getOrderBy()).isEqualTo("name");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    /** 默认分页（不传参数）应当落到第 1 页、每页 20 条、方向 asc。 */
    @Test
    void listWithoutParametersUsesDocumentedDefaults() throws Exception {
        when(categoryService.page(any())).thenReturn(
                new PageResult<>(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get(CATEGORIES).with(categoryManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        ArgumentCaptor<CategoryQuery> captor = ArgumentCaptor.forClass(CategoryQuery.class);
        verify(categoryService).page(captor.capture());
        CategoryQuery query = captor.getValue();
        assertThat(query.getPageNo()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(20);
        assertThat(query.getKeyword()).isNull();
        assertThat(query.getStatus()).isNull();
        assertThat(query.getOrderBy()).isNull();
        assertThat(query.getOrderDirection()).isEqualTo("asc");
    }

    @Test
    void listRejectsInvalidPagingStatusAndKeyword() throws Exception {
        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("status", "ARCHIVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("status")));

        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("pageNo", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("keyword", "k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(categoryService);
    }

    /**
     * 排序字段白名单（{@code name}、{@code status}、{@code sort_order}、{@code created_at}、
     * {@code updated_at}）在 {@code CategoryServiceImpl} 里判定，不在 Web 层；本用例验证
     * 服务抛出的 {@code 400/VALIDATION_FAILED} 原样映射，不会被兜底分支改写成 500。
     */
    @Test
    void listRejectsOrderFieldOutsideWhitelistThroughServiceValidation() throws Exception {
        when(categoryService.page(any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "排序字段不在允许范围内"));

        mockMvc.perform(get(CATEGORIES)
                        .with(categoryManager())
                        .queryParam("orderBy", "id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 创建 ----------

    @Test
    void categoryManagerCanCreateCategoryAndCommandReachesService() throws Exception {
        when(categoryService.create(any())).thenReturn(sampleCategory("ENABLED", 1L));

        mockMvc.perform(post(CATEGORIES)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  办公设备  ", "sortOrder": 10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(CATEGORY_ID))
                .andExpect(jsonPath("$.data.name").value("办公设备"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.version").value(1));

        ArgumentCaptor<CreateCategoryCommand> captor =
                ArgumentCaptor.forClass(CreateCategoryCommand.class);
        verify(categoryService).create(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("办公设备");
        assertThat(captor.getValue().sortOrder()).isEqualTo(10);
    }

    @ParameterizedTest(name = "create rejects {0}")
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidBody(String caseName, String body) throws Exception {
        mockMvc.perform(post(CATEGORIES)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(categoryService, never()).create(any());
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("a blank name", """
                        {"name": "   ", "sortOrder": 10}
                        """),
                Arguments.of("a name over 100 characters", """
                        {"name": "%s", "sortOrder": 10}
                        """.formatted("c".repeat(101))),
                Arguments.of("a missing sort order", """
                        {"name": "办公设备"}
                        """),
                Arguments.of("a negative sort order", """
                        {"name": "办公设备", "sortOrder": -1}
                        """)
        );
    }

    /** 名称已存在由服务判定，Web 层只透传稳定错误码。 */
    @Test
    void duplicateCategoryNameIsReportedAsConflict() throws Exception {
        when(categoryService.create(any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CATEGORY_NAME_CONFLICT",
                        "分类名称已存在"));

        mockMvc.perform(post(CATEGORIES)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_CONFLICT"));
    }

    // ---------- 修改名称与排序值 ----------

    @Test
    void categoryManagerCanUpdateNameAndSortOrderAndCommandReachesService() throws Exception {
        when(categoryService.update(eq(CATEGORY_ID), any()))
                .thenReturn(sampleCategory("DISABLED", 3L));

        mockMvc.perform(put(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "办公设备与耗材", "sortOrder": 20, "version": 2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("办公设备"));

        ArgumentCaptor<UpdateCategoryCommand> captor =
                ArgumentCaptor.forClass(UpdateCategoryCommand.class);
        verify(categoryService).update(eq(CATEGORY_ID), captor.capture());
        UpdateCategoryCommand command = captor.getValue();
        assertThat(command.name()).isEqualTo("办公设备与耗材");
        assertThat(command.sortOrder()).isEqualTo(20);
        assertThat(command.version()).isEqualTo(2L);
    }

    @Test
    void updateRejectsMissingVersionAndBlankName() throws Exception {
        mockMvc.perform(put(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "办公设备", "sortOrder": 10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("version")));

        mockMvc.perform(put(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "sortOrder": 10, "version": 2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(categoryService, never()).update(anyLong(), any());
    }

    // ---------- 启用 / 停用 ----------

    @Test
    void categoryManagerCanEnableCategoryAndCommandReachesService() throws Exception {
        when(categoryService.enable(eq(CATEGORY_ID), any()))
                .thenReturn(sampleCategory("ENABLED", 4L));

        mockMvc.perform(post(CATEGORIES + "/" + CATEGORY_ID + "/actions/enable")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.version").value(4));

        ArgumentCaptor<CategoryStatusChangeCommand> captor =
                ArgumentCaptor.forClass(CategoryStatusChangeCommand.class);
        verify(categoryService).enable(eq(CATEGORY_ID), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(2L);
    }

    @Test
    void categoryManagerCanDisableCategoryAndCommandReachesService() throws Exception {
        when(categoryService.disable(eq(CATEGORY_ID), any()))
                .thenReturn(sampleCategory("DISABLED", 4L));

        mockMvc.perform(post(CATEGORIES + "/" + CATEGORY_ID + "/actions/disable")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        verify(categoryService).disable(eq(CATEGORY_ID), any());
    }

    @Test
    void enableAndDisableRejectMissingVersion() throws Exception {
        mockMvc.perform(post(CATEGORIES + "/" + CATEGORY_ID + "/actions/enable")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(CATEGORIES + "/" + CATEGORY_ID + "/actions/disable")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(categoryService);
    }

    // ---------- 删除 ----------

    @Test
    void categoryManagerCanDeleteCategoryAndResponseCarriesNoData() throws Exception {
        mockMvc.perform(delete(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .queryParam("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                // 删除类端点统一返回 R<Void>：全局 non_null 策略下 data 整体不出现
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(categoryService).delete(CATEGORY_ID, 2L);
    }

    /** 8.4 契约把期望版本放在必填查询参数上，缺参数属于请求写错而不是服务端故障。 */
    @Test
    void deleteWithoutVersionParameterIsRejectedAsValidationFailure() throws Exception {
        mockMvc.perform(delete(CATEGORIES + "/" + CATEGORY_ID).with(categoryManager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(categoryService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void deleteRejectsNonNumericVersion() throws Exception {
        mockMvc.perform(delete(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .queryParam("version", "latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(categoryService, never()).delete(anyLong(), anyLong());
    }

    // ---------- 服务异常 → HTTP 契约 ----------

    /** 非正整数 ID 由服务按"资源不存在"处理（{@code CategoryServiceImplTest} 已覆盖判定本身）。 */
    @Test
    void nonPositiveCategoryIdIsReportedAsNotFound() throws Exception {
        when(categoryService.update(eq(0L), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND",
                        "分类不存在"));
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "分类不存在"))
                .when(categoryService).delete(eq(0L), anyLong());

        mockMvc.perform(put(CATEGORIES + "/0")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        mockMvc.perform(delete(CATEGORIES + "/0")
                        .with(categoryManager())
                        .queryParam("version", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    /** 路径变量不是数字属于调用方写错请求，不能落到兜底分支变成 500。 */
    @Test
    void nonNumericCategoryIdIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(put(CATEGORIES + "/abc")
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(categoryService);
    }

    @Test
    void missingCategoryIsReportedAsNotFound() throws Exception {
        when(categoryService.update(eq(CATEGORY_ID), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND",
                        "分类不存在"));

        mockMvc.perform(put(CATEGORIES + "/" + CATEGORY_ID)
                        .with(categoryManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    /** 三个 409 错误码分别对应版本冲突、重名与仍被工单引用。 */
    @ParameterizedTest(name = "{0} is reported as 409")
    @MethodSource("conflictRequests")
    void categoryConflictsAreReportedAsConflict(
            String code, MockHttpServletRequestBuilder request) throws Exception {
        // 每个用例只命中一个端点，其余两处桩按宽松处理
        lenient().when(categoryService.update(eq(CATEGORY_ID), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, code, "分类状态已变化"));
        lenient().when(categoryService.create(any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, code, "分类状态已变化"));
        lenient().doThrow(new ApiException(HttpStatus.CONFLICT, code, "分类状态已变化"))
                .when(categoryService).delete(eq(CATEGORY_ID), anyLong());

        mockMvc.perform(request.with(categoryManager()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code));
    }

    static Stream<Arguments> conflictRequests() {
        return Stream.of(
                Arguments.of("CATEGORY_CONFLICT",
                        put(CATEGORIES + "/" + CATEGORY_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody())),
                Arguments.of("CATEGORY_NAME_CONFLICT",
                        post(CATEGORIES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody())),
                Arguments.of("CATEGORY_IN_USE",
                        delete(CATEGORIES + "/" + CATEGORY_ID).queryParam("version", "2"))
        );
    }

    // ---------- 辅助 ----------

    private static CategoryResult sampleCategory(String status, long version) {
        return new CategoryResult(
                CATEGORY_ID, "办公设备", status, 10, CREATED_AT, UPDATED_AT, version);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    categoryManager() {
        return user("category-admin").authorities(
                List.of(new SimpleGrantedAuthority("CATEGORY_MANAGE")));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    ticketCreator() {
        return user("employee").authorities(
                List.of(new SimpleGrantedAuthority("TICKET_CREATE")));
    }

    private static String createBody() {
        return """
                {"name": "办公设备", "sortOrder": 10}
                """;
    }

    private static String updateBody() {
        return """
                {"name": "办公设备与耗材", "sortOrder": 20, "version": 2}
                """;
    }

    private static String statusBody() {
        return """
                {"version": 2}
                """;
    }
}
