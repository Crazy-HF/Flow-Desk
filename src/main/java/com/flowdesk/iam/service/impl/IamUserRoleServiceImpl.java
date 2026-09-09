package com.flowdesk.iam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.bo.IamUserRoleBO;
import com.flowdesk.iam.domain.vo.GrantURVO;
import com.flowdesk.iam.domain.vo.IamUserRoleVO;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.service.IamUserRoleService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flowdesk.iam.service.IamUserService;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import com.flowdesk.shared.web.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户与角色多对多关系 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Service
public class IamUserRoleServiceImpl extends ServiceImpl<IamUserRoleMapper, IamUserRole> implements IamUserRoleService {

    private final IamUserRoleMapper iamUserRoleMapper;
    public IamUserRoleServiceImpl(IamUserRoleMapper iamUserRoleMapper) {
        this.iamUserRoleMapper = iamUserRoleMapper;
    }

    @Autowired
    private IamUserService userService;
    /**
     * 按用户或角色分页查询授权关系。
     */
    @Override
    public PageResult<IamUserRoleBO> getUserRolePage(IamUserRoleVO userRoleVO, PageQuery pageQuery) {

        return null;
    }

    /**
     * 为用户授予角色。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean grantRole(GrantURVO grantURVO) {
        // 1. 参数校验
        if (grantURVO == null
                || grantURVO.getUserId() == null
                || CollectionUtils.isEmpty(grantURVO.getRoleIds())) {
            throw new IllegalArgumentException("用户ID或角色列表不能为空");
        }

        Long userId = grantURVO.getUserId();

        // 2. 去重、去 null
        Set<Long> requestRoleIds = grantURVO.getRoleIds().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requestRoleIds.isEmpty()) {
            throw new IllegalArgumentException("角色列表不能为空");
        }

        // 3. 检查用户是否存在、是否启用
        userService.getIamUserById(userId);

        // 4. 检查角色是否存在、是否启用、是否允许分配
        // List<IamRole> roles = roleService.listByIds(requestRoleIds);
        // if (roles.size() != requestRoleIds.size()) {
        //     throw new IllegalArgumentException("存在无效角色");
        // }
        // 检查角色状态、是否可分配、是否越权分配等

        // 5. 查询已存在授权，用 Set 提高 contains 效率
        List<Long> existingList = iamUserRoleMapper.selectRoleIdsByUserId(userId);
        Set<Long> existingRoleIds = existingList == null
                ? Collections.emptySet()
                : new HashSet<>(existingList);

        // 6. 过滤出需要新增的角色
        List<Long> newRoleIds = requestRoleIds.stream()
                .filter(roleId -> !existingRoleIds.contains(roleId))
                .collect(Collectors.toList());

        // 7. 幂等：都已经存在，直接成功
        if (newRoleIds.isEmpty())
            return true;

        // 8. 职责分离 SoD、互斥角色、最大角色数等校验
        // checkGrantRoleConstraint(userId, newRoleIds, existingRoleIds);

        // 9. 批量插入
        int insertedCount = iamUserRoleMapper.batchInsertUserRoles(userId, newRoleIds);

        if (insertedCount != newRoleIds.size()) {
            throw new IllegalStateException("授权失败，插入数量不一致");
        }

        // 10. 清理权限缓存、刷新会话、token 版本号 +1
        // permissionCacheService.evictUser(userId);
        // tokenService.bumpVersion(userId);

        return true;
    }

    /**
     * 撤销用户角色。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean revokeRoles(Long userId, List<Long> roleIds) {
        if (userId == null || CollectionUtils.isEmpty(roleIds)) {
            throw new IllegalArgumentException("用户ID或角色列表不能为空");
        }

        // 2. 检查用户是否存在
        userService.getIamUserById(userId);

        // 3. 去重
        Set<Long> distinctRoleIds = roleIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (distinctRoleIds.isEmpty())
            return true;

        //TODO 前置校验

        // 4. 批量删除授权关系
        LambdaQueryWrapper<IamUserRole> wrapper = new LambdaQueryWrapper<IamUserRole>()
                .eq(IamUserRole::getUserId, userId)
                .in(IamUserRole::getRoleId, distinctRoleIds);

        iamUserRoleMapper.delete(wrapper);

        //TODO清除权限缓存

        return true;
    }

    /**
     * 撤销用户角色
     */
    @Override
    public Boolean revokeRole(Long userId, Long roleId) {
        if (roleId == null) {
            throw new IllegalArgumentException("角色ID不能为空");
        }
        return revokeRoles(userId, Collections.singletonList(roleId));
    }
}
