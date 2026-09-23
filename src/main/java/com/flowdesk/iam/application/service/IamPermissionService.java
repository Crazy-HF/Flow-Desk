package com.flowdesk.iam.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.PermissionResult;
import com.flowdesk.iam.application.command.CreatePermissionCommand;
import com.flowdesk.iam.application.query.PermissionQuery;
import com.flowdesk.iam.application.command.UpdatePermissionCommand;

/**
 * 权限管理服务。
 *
 * <p>定义权限 CRUD 的业务边界，包括编码唯一性、内置权限保护和角色引用保护。</p>
 */
public interface IamPermissionService {

    /**
     * 按条件分页查询权限。
     *
     * @param query 分页、关键字和排序条件
     * @return 权限分页结果，每项包含引用该权限的角色 ID
     */
    PageResult<PermissionResult> page(PermissionQuery query);

    /**
     * 查询指定权限详情。
     *
     * @param permissionId 权限 ID
     * @return 权限详情
     * @throws com.flowdesk.common.exception.ApiException 权限不存在时抛出
     */
    PermissionResult getById(long permissionId);

    /**
     * 创建编码唯一的权限。
     *
     * @param request 权限创建参数
     * @return 已创建的权限
     * @throws com.flowdesk.common.exception.ApiException 权限编码重复时抛出
     */
    PermissionResult create(CreatePermissionCommand request);

    /**
     * 更新权限名称和描述，权限编码保持不变。
     *
     * @param permissionId 权限 ID
     * @param request 权限更新参数
     * @return 更新后的权限详情
     * @throws com.flowdesk.common.exception.ApiException 权限不存在时抛出
     */
    PermissionResult update(
            long permissionId,
            UpdatePermissionCommand request
    );

    /**
     * 删除权限。
     *
     * @param permissionId 权限 ID
     * @throws com.flowdesk.common.exception.ApiException 权限不存在、权限为
     *         {@code RBAC_MANAGE} 或仍被角色引用时抛出
     */
    void delete(long permissionId);
}
