package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.iam.domain.*;
import com.flowdesk.iam.application.result.AuthenticationSnapshot;
import com.flowdesk.iam.application.result.UserProfileSnapshot;
import com.flowdesk.iam.mapper.*;
import com.flowdesk.iam.application.service.IamAuthService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IamAuthServiceImpl implements IamAuthService {

    private final IamUserMapper iamUserMapper;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamRoleMapper iamRoleMapper;
    private final IamRolePermissionMapper iamRolePermissionMapper;
    private final IamPermissionMapper iamPermissionMapper;

    public IamAuthServiceImpl(IamUserMapper iamUserMapper,
                              IamUserRoleMapper iamUserRoleMapper,
                              IamRoleMapper iamRoleMapper,
                              IamRolePermissionMapper iamRolePermissionMapper,
                              IamPermissionMapper iamPermissionMapper) {
        this.iamUserMapper = iamUserMapper;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamRoleMapper = iamRoleMapper;
        this.iamRolePermissionMapper = iamRolePermissionMapper;
        this.iamPermissionMapper = iamPermissionMapper;
    }
    /** 登录认证:查询用户角色，权限编码 */
    @Override
    public AuthenticationSnapshot findByUsername(String username) {
        IamUser iamUser = iamUserMapper.selectOne(new LambdaQueryWrapper<IamUser>()
                .eq(IamUser::getUsername, username));
        if (iamUser == null)
            return null;

        List<Long> roleIds = iamUserRoleMapper.selectList(new LambdaQueryWrapper<IamUserRole>()
                .eq(IamUserRole::getUserId, iamUser.getId()))
                .stream().map(IamUserRole::getRoleId).toList();

        //无角色时必须在 in() 之前返回：空集合会拼出 IN ()，MySQL 语法错误
        if(roleIds.isEmpty())
            return toResult(iamUser, List.of(), List.of());

        List<String> roleCodes = iamRoleMapper.selectByIds(roleIds)
                .stream().map(IamRole::getCode).toList();

        List<Long> permissionIds = iamRolePermissionMapper.selectList(new LambdaQueryWrapper<IamRolePermission>()
                .in(IamRolePermission::getRoleId, roleIds))
                .stream().map(IamRolePermission::getPermissionId).distinct().toList();

        if(permissionIds.isEmpty())
            return toResult(iamUser, roleCodes, List.of());

        List<String> permissionCodes = iamPermissionMapper.selectByIds(permissionIds)
                .stream().map(IamPermission::getCode).sorted().toList();

        return toResult(iamUser, roleCodes, permissionCodes);
    }

    /** 查询认证响应所需的最新用户资料；不存在时返回 null。 */
    @Override
    public UserProfileSnapshot findProfileById(long userId) {
        IamUser user = iamUserMapper.selectProfileById(userId);

        if (user == null) {
            return null;
        }

        return new UserProfileSnapshot(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getStatus()
        );
    }


    /**
     * 转换为认证对象
     */
    private AuthenticationSnapshot toResult(IamUser iamUser, List<String> roleCodes, List<String> permissionCodes) {
        return new AuthenticationSnapshot(iamUser.getId(), iamUser.getUsername(),
                iamUser.getPassword(), iamUser.getStatus(), roleCodes, permissionCodes);
    }


}
