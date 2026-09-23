package com.flowdesk.iam.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;

import java.util.List;

/**
 * 用户角色授权服务。
 */
public interface IamUserRoleService {

    /**
     * 按用户或角色分页查询授权关系。
     */
    PageResult<UserRoleResult> page(UserRoleQuery query);

    /**
     * 授予用户角色。
     * 重复授权保持幂等，返回原授权记录。
     */
    List<UserRoleResult> grant(GrantUserRolesCommand request);

    /**
     * 撤销用户角色。
     */
    void revoke(long userId, long roleId);

    /**
     * 在一个事务中撤销指定用户的多个角色。
     */
    void revokeBatch(RevokeUserRolesCommand request);

    /**
     * 清空指定用户的全部角色。
     */
    void clearAll(long userId);

    /**
     * 向多个用户授予同一个角色。
     */
    List<UserRoleResult> grantUsers(GrantRoleToUsersCommand request);
}
