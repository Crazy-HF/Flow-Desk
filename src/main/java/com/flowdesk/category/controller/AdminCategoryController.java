package com.flowdesk.category.controller;

import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.query.CategoryQuery;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类管理接口，前缀 {@code /fd/v1/admin/categories}（{@code docs/api-design.md} 8.4）。
 *
 * <p>类级 {@code @PreAuthorize} 让本控制器所有端点都要求 {@code CATEGORY_MANAGE}；只有创建返回
 * {@code 201}，其余成功返回 {@code 200}。本层不写业务规则：版本判定、名称唯一与引用约束都在
 * {@code CategoryService} 实现里，异常由 {@code GlobalExceptionHandler} 映射成稳定错误码。</p>
 *
 * <p>与 {@link CategoryController}（{@code /fd/v1/categories/options}）分开：那个端点服务员工提交工单，
 * 只要求 {@code TICKET_CREATE} 或 {@code TICKET_PROCESS}，返回最小字段；管理端点改的是业务配置，
 * 不能合到同一个控制器上共享权限注解。</p>
 */
@RestController
@RequestMapping("/fd/v1/admin/categories")
@PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 分页查询分类，启用与停用一起返回。
     *
     * @param query 查询串绑定（{@code @ModelAttribute}）：分页参数来自 {@code PageQuery}，
     *              另有可选 {@code keyword} 与 {@code status}；{@code @Valid} 负责页码、
     *              每页大小、排序参数与状态取值的校验
     * @return {@code 200} 与分类分页结果，默认按展示顺序排列
     */
    @GetMapping
    public R<PageResult<CategoryResult>> page(@Valid @ModelAttribute CategoryQuery query) {
        return R.success(categoryService.page(query));
    }

    /**
     * 新建分类。
     *
     * @param request 请求体：{@code name} 与 {@code sortOrder}；格式与长度由 {@code @Valid} 校验
     * @return {@code 201} 与创建后的分类（状态为启用）；这是本控制器唯一的 {@code 201}
     */
    @PostMapping
    public ResponseEntity<R<CategoryResult>> create(
            @Valid @RequestBody CreateCategoryCommand request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(categoryService.create(request)));
    }

    /**
     * 修改分类的名称或排序值。
     *
     * @param categoryId 路径中的分类 ID；非正整数按"资源不存在"处理
     * @param request    请求体：新名称、新排序值与期望版本
     * @return {@code 200} 与修改后的分类
     */
    @PutMapping("/{categoryId}")
    public R<CategoryResult> update(
            @PathVariable long categoryId,
            @Valid @RequestBody UpdateCategoryCommand request) {
        return R.success(categoryService.update(categoryId, request));
    }

    /**
     * 启用分类。
     *
     * @param categoryId 路径中的分类 ID
     * @param request    请求体：期望版本
     * @return {@code 200} 与启用后的分类
     */
    @PostMapping("/{categoryId}/actions/enable")
    public R<CategoryResult> enable(
            @PathVariable long categoryId,
            @Valid @RequestBody CategoryStatusChangeCommand request) {
        return R.success(categoryService.enable(categoryId, request));
    }

    /**
     * 停用分类。停用不影响历史工单，但新工单不能再选它。
     *
     * @param categoryId 路径中的分类 ID
     * @param request    请求体：期望版本
     * @return {@code 200} 与停用后的分类
     */
    @PostMapping("/{categoryId}/actions/disable")
    public R<CategoryResult> disable(
            @PathVariable long categoryId,
            @Valid @RequestBody CategoryStatusChangeCommand request) {
        return R.success(categoryService.disable(categoryId, request));
    }

    /**
     * 删除分类。
     *
     * <p>按 8.4 契约，期望版本经<b>必填查询参数</b> {@code version} 传递，请求体为空。
     * 缺少参数或参数不是数字时由 {@code GlobalExceptionHandler} 统一映射成
     * {@code 400/VALIDATION_FAILED}，不会被记成服务端错误。</p>
     *
     * @param categoryId 路径中的分类 ID
     * @param version    期望版本
     * @return {@code 200} 与空数据信封（删除类端点统一返回 {@code R<Void>}）
     */
    @DeleteMapping("/{categoryId}")
    public R<Void> delete(
            @PathVariable long categoryId,
            @RequestParam("version") long version) {
        categoryService.delete(categoryId, version);
        return R.success();
    }
}
