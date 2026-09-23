package com.flowdesk.iam.application.service;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.RoleResult;
import com.flowdesk.iam.application.command.CreateRoleCommand;
import com.flowdesk.iam.application.query.RoleQuery;
import com.flowdesk.iam.application.command.UpdateRoleCommand;

import java.util.List;

/**
 * 角色管理服务。
 *
 * <p>契约见 {@code docs/api-design.md} 8.2.1 与 {@code docs/modules/rbac.md} 4.1；
 * 业务规则、保护规则与事务边界都在实现层，控制器只负责权限与 HTTP 语义。</p>
 */
public interface IamRoleService {

    /**
     * 分页查询角色。只读不加锁，{@code keyword} 匹配 {@code code} 与 {@code name}。
     *
     * @param query 分页与筛选参数；排序字段或方向非法时 {@code 400/VALIDATION_FAILED}
     * @return 角色分页结果，每项含已授权的 {@code permissionIds}
     */
    PageResult<RoleResult> page(RoleQuery query);

    /**
     * 查询角色详情。
     *
     * @param roleId 角色 ID；非正整数按“资源不存在”处理，不返回 400
     * @return 角色详情，含已授权的 {@code permissionIds}
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}
     */
    RoleResult getById(long roleId);

    /**
     * 创建角色，并可在同一事务中授予多个权限。{@code code} 创建后不可修改。
     *
     * @param request 角色编码、名称、可选描述与可选权限 ID 列表
     * @return 创建后的角色及其权限 ID
     * @throws ApiException {@code 404/PERMISSION_NOT_FOUND}、{@code 409/ROLE_CODE_CONFLICT}
     */
    RoleResult create(CreateRoleCommand request);

    /**
     * 修改角色的名称与描述。请求体没有 {@code code}，编码改不了（阶段设计决策 8）。
     *
     * @param roleId  角色 ID
     * @param request 新名称与可选描述
     * @return 修改后的角色，含已授权的 {@code permissionIds}
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}
     */
    RoleResult update(long roleId, UpdateRoleCommand request);

    /**
     * 删除角色。{@code SYSTEM_ADMIN} 与仍被授权关系引用的角色都拒绝删除，不做级联清理。
     *
     * @param roleId 角色 ID
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}、{@code 409/RBAC_CONFLICT}
     */
    void delete(long roleId);
}
