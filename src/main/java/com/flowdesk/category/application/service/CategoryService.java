package com.flowdesk.category.application.service;

import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.query.CategoryQuery;
import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.common.web.PageResult;
import java.util.List;

/**
 * 分类应用服务。
 *
 * <p>两类调用方共用这一个接口：{@link #options()} 与 {@link #isEnabled(long)} 服务工单流程
 * （提交时选分类、校验分类可用），其余方法服务管理端的分类管理（{@code docs/api-design.md} 8.4，
 * 权限 {@code CATEGORY_MANAGE}）。业务规则、版本判定与事务边界都在实现层。</p>
 */
public interface CategoryService {

    /**
     * 获取按展示顺序排列的启用分类。
     */
    List<CategoryOptionResult> options();

    /**
     * 检查分类是否启用。
     */
    boolean isEnabled(long categoryId);

    /**
     * 分页查询分类，启用与停用一起返回。只读不加锁。
     *
     * @param query 分页与筛选参数；排序字段或方向非法时 {@code 400/VALIDATION_FAILED}
     * @return 分类分页结果
     */
    PageResult<CategoryResult> page(CategoryQuery query);

    /**
     * 新建分类，落库即为启用状态。
     *
     * @param request 名称与排序值；名称去首尾空白后在全表唯一
     * @return 创建后的分类
     * @throws com.flowdesk.common.exception.ApiException {@code 409/CATEGORY_NAME_CONFLICT}
     */
    CategoryResult create(CreateCategoryCommand request);

    /**
     * 修改分类的名称或排序值。改名不改变工单与分类的归属关系。
     *
     * @param categoryId 分类 ID；非正整数按"资源不存在"处理
     * @param request    新名称、新排序值与期望版本
     * @return 修改后的分类
     * @throws com.flowdesk.common.exception.ApiException
     *         {@code 404/CATEGORY_NOT_FOUND}、{@code 409/CATEGORY_NAME_CONFLICT}、
     *         {@code 409/CATEGORY_CONFLICT}
     */
    CategoryResult update(long categoryId, UpdateCategoryCommand request);

    /**
     * 启用分类。启用后员工在「新建工单」里才能再次选到它。
     *
     * @param categoryId 分类 ID
     * @param request    期望版本
     * @return 启用后的分类；已启用时为幂等成功，不修改版本
     * @throws com.flowdesk.common.exception.ApiException
     *         {@code 404/CATEGORY_NOT_FOUND}、{@code 409/CATEGORY_CONFLICT}
     */
    CategoryResult enable(long categoryId, CategoryStatusChangeCommand request);

    /**
     * 停用分类。停用不影响历史工单，但新工单与分类调整都不能再选它。
     *
     * @param categoryId 分类 ID
     * @param request    期望版本
     * @return 停用后的分类；已停用时为幂等成功，不修改版本
     * @throws com.flowdesk.common.exception.ApiException
     *         {@code 404/CATEGORY_NOT_FOUND}、{@code 409/CATEGORY_CONFLICT}
     */
    CategoryResult disable(long categoryId, CategoryStatusChangeCommand request);

    /**
     * 删除分类。只有从未被工单引用的分类才能删除，不做级联清理。
     *
     * @param categoryId 分类 ID
     * @param version    期望版本，来自 8.4 契约的必填查询参数
     * @throws com.flowdesk.common.exception.ApiException
     *         {@code 404/CATEGORY_NOT_FOUND}、{@code 409/CATEGORY_IN_USE}、
     *         {@code 409/CATEGORY_CONFLICT}
     */
    void delete(long categoryId, long version);
}
