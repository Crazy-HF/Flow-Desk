package com.flowdesk.iam.service;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamRoleCreateVO;
import com.flowdesk.iam.domain.vo.IamRoleQueryVO;
import com.flowdesk.iam.domain.vo.IamRoleUpdateVO;

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
    PageResult<IamRoleBO> page(IamRoleQueryVO query);

    /**
     * 查询角色详情。
     *
     * @param roleId 角色 ID；非正整数按“资源不存在”处理，不返回 400
     * @return 角色详情，含已授权的 {@code permissionIds}
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}
     */
    IamRoleBO getById(long roleId);

    /**
     * 创建角色。{@code code} 创建后不可修改；新角色不含任何权限。
     *
     * @param request 角色编码、名称与可选描述
     * @return 创建后的角色，{@code permissionIds} 为空
     * @throws ApiException {@code 409/ROLE_CODE_CONFLICT}
     */
    IamRoleBO create(IamRoleCreateVO request);

    /**
     * 修改角色的名称与描述。请求体没有 {@code code}，编码改不了（阶段设计决策 8）。
     *
     * @param roleId  角色 ID
     * @param request 新名称与可选描述
     * @return 修改后的角色，含已授权的 {@code permissionIds}
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}
     */
    IamRoleBO update(long roleId, IamRoleUpdateVO request);

    /**
     * 删除角色。{@code SYSTEM_ADMIN} 与仍被授权关系引用的角色都拒绝删除，不做级联清理。
     *
     * @param roleId 角色 ID
     * @throws ApiException {@code 404/ROLE_NOT_FOUND}、{@code 409/RBAC_CONFLICT}
     */
    void delete(long roleId);
}
