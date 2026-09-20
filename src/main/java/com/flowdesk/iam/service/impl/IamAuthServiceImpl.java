package com.flowdesk.iam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.iam.domain.*;
import com.flowdesk.iam.domain.bo.IamAuthBO;
import com.flowdesk.iam.mapper.*;
import com.flowdesk.iam.service.IamAuthService;
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
    public IamAuthBO findByUsername(String username) {
        IamUser iamUser = iamUserMapper.selectOne(new LambdaQueryWrapper<IamUser>()
                .eq(IamUser::getUsername, username));
        if (iamUser == null)
            return null;

        List<Long> roleIds = iamUserRoleMapper.selectList(new LambdaQueryWrapper<IamUserRole>()
                .eq(IamUserRole::getUserId, iamUser.getId()))
                .stream().map(IamUserRole::getRoleId).toList();

        //无角色时必须在 in() 之前返回：空集合会拼出 IN ()，MySQL 语法错误
        if(roleIds.isEmpty())
            return toBO(iamUser, List.of(), List.of());

        List<String> roleCodes = iamRoleMapper.selectByIds(roleIds)
                .stream().map(IamRole::getCode).toList();

        List<Long> permissionIds = iamRolePermissionMapper.selectList(new LambdaQueryWrapper<IamRolePermission>()
                .in(IamRolePermission::getRoleId, roleIds))
                .stream().map(IamRolePermission::getPermissionId).distinct().toList();

        if(permissionIds.isEmpty())
            return toBO(iamUser, roleCodes, List.of());

        List<String> permissionCodes = iamPermissionMapper.selectByIds(permissionIds)
                .stream().map(IamPermission::getCode).sorted().toList();

        return toBO(iamUser, roleCodes, permissionCodes);
    }

    /**
     * 转换为认证对象
     */
    private IamAuthBO toBO(IamUser iamUser, List<String> roleCodes, List<String> permissionCodes) {
        return new IamAuthBO(iamUser.getId(), iamUser.getUsername(), iamUser.getDisplayName(),
                iamUser.getPassword(), iamUser.getStatus(), roleCodes, permissionCodes);
    }
}
