package com.flowdesk.iam.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;

import java.util.List;

/**
 * 角色权限授权服务。
 */
public interface IamRolePermissionService {

    /**
     * 按角色或权限分页查询授权关系。
     */
    PageResult<RolePermissionResult> page(RolePermissionQuery query);

    /**
     * 授予角色权限。
     * 重复授权保持幂等，返回原授权记录。
     */
    List<RolePermissionResult> grant(GrantRolePermissionsCommand request);

    /**
     * 撤销角色单个权限。
     */
    void revoke(long roleId, long permissionId);

    /**
     * 在一个事务中撤销指定角色的多个权限。
     */
    void revokeBatch(RevokeRolePermissionsCommand request);

    /**
     * 清空指定角色的全部权限。
     */
    void clearAll(long roleId);
}
