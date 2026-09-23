package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.iam.application.service.IamUserRoleService;
import com.flowdesk.iam.application.port.SessionRevocationPort;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IamUserRoleServiceImpl implements IamUserRoleService {
    //系统管理员角色码
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    //排序白名单
    private static final Set<String> USER_ROLE_ORDER_FIELDS =
            Set.of("granted_at");

    private final Clock clock;
    private final CurrentOperatorPort currentOperatorPort;
    private final SessionRevocationPort sessionRevocationPort;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamUserMapper iamUserMapper;
    private final IamRoleMapper iamRoleMapper;

    public IamUserRoleServiceImpl(Clock clock, CurrentOperatorPort currentOperatorPort, SessionRevocationPort sessionRevocationPort, IamUserRoleMapper iamUserRoleMapper, IamUserMapper iamUserMapper, IamRoleMapper iamRoleMapper) {
        this.clock = clock;
        this.currentOperatorPort = currentOperatorPort;
        this.sessionRevocationPort = sessionRevocationPort;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamUserMapper = iamUserMapper;
        this.iamRoleMapper = iamRoleMapper;
    }

    /**
     *
     * @param query
     * @return
     */
    @Override
    public PageResult<UserRoleResult> page(UserRoleQuery query) {
        Page<IamUserRole> page = new Page<>(query.getCurrent(), query.getSize());
        //2.创建排序字段
        List<OrderItem> orderItems = query.orderItems(USER_ROLE_ORDER_FIELDS);
        if(orderItems.isEmpty()){
            page.addOrder(OrderItem.desc("granted_at"));
        }else {
            page.addOrder(orderItems);
        }
        page.addOrder(OrderItem.asc("user_id"), OrderItem.asc("role_id"));

        //3.模糊查询
        LambdaQueryWrapper<IamUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(
                query.getUserId() != null,
                IamUserRole::getUserId,
                query.getUserId()
        ).eq(
                query.getRoleId() != null,
                IamUserRole::getRoleId,
                query.getRoleId()
        );

        //4.分页查询
        Page<IamUserRole> resultPage = iamUserRoleMapper.selectPage(page, wrapper);

        if(resultPage.getRecords().isEmpty()){
            return PageResult.from(resultPage,
                    iamUserRole ->
                            toResult(iamUserRole, Map.of(), Map.of()));
        }

        //5.批量收集 userId 和 roleId
        Set<Long> userIds = resultPage.getRecords().stream().map(IamUserRole::getUserId).collect(Collectors.toSet());
        Set<Long> roleIds = resultPage.getRecords().stream().map(IamUserRole::getRoleId).collect(Collectors.toSet());

        //6.分别 selectBatchIds 查询用户、角色
        Map<Long, IamUser> userMap = iamUserMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(IamUser::getId, Function.identity()));
        Map<Long, IamRole> roleMap = iamRoleMapper.selectBatchIds(roleIds).stream().collect(Collectors.toMap(IamRole::getId, Function.identity()));

        //7.转成 Map<Long, IamUser> 和 Map<Long, IamRole>
        //8.组装 UserRoleResult
        return PageResult.from(resultPage, iamUserRole -> toResult(iamUserRole, userMap, roleMap));
    }

    @Override
    @Transactional
    public List<UserRoleResult> grant(GrantUserRolesCommand request) {
        try {
            return doGrant(request);
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    @Override
    @Transactional
    public void revoke(long userId, long roleId) {
        try {
            doRevoke(userId, roleId);
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 批量撤销一个用户的多个角色。
     *
     * <p>请求先去重并按角色 ID 升序归一化；任一用户、角色或授权关系不存在时，
     * 整批失败。允许撤销后用户不再拥有任何角色，但仍保护最后一个启用的
     * {@code SYSTEM_ADMIN}。实际删除前只撤销目标用户会话一次。</p>
     */
    @Override
    @Transactional
    public void revokeBatch(RevokeUserRolesCommand request) {
        try {
            List<Long> roleIds = request.roleIds().stream()
                    .distinct()
                    .sorted()
                    .toList();

            Map<Long, IamRole> roleMap = iamRoleMapper.selectByIdsForUpdate(roleIds)
                    .stream()
                    .collect(Collectors.toMap(IamRole::getId, Function.identity()));
            if (roleMap.size() != roleIds.size()) {
                throw roleNotFound();
            }

            IamUser user = iamUserMapper.selectByIdForUpdate(request.userId());
            if (user == null) {
                throw userNotFound();
            }

            Map<Long, IamUserRole> grantsByRoleId =
                    iamUserRoleMapper.selectByUserIdForUpdate(request.userId())
                            .stream()
                            .collect(Collectors.toMap(
                                    IamUserRole::getRoleId,
                                    Function.identity()
                            ));
            boolean missingGrant = roleIds.stream()
                    .anyMatch(roleId -> !grantsByRoleId.containsKey(roleId));
            if (missingGrant) {
                throw grantNotFound();
            }

            boolean removesSystemAdmin = roleIds.stream()
                    .map(roleMap::get)
                    .anyMatch(role -> SYSTEM_ADMIN.equals(role.getCode()));
            if (removesSystemAdmin && user.getStatus() == IamUserStatus.ENABLED) {
                Long systemAdminRoleId = roleIds.stream()
                        .filter(roleId -> SYSTEM_ADMIN.equals(roleMap.get(roleId).getCode()))
                        .findFirst()
                        .orElseThrow();

                List<Long> adminUserIds = iamUserRoleMapper.selectList(
                                new LambdaQueryWrapper<IamUserRole>()
                                        .eq(IamUserRole::getRoleId, systemAdminRoleId)
                        ).stream()
                        .map(IamUserRole::getUserId)
                        .toList();
                long enabledAdminCount = iamUserMapper.selectCount(
                        new LambdaQueryWrapper<IamUser>()
                                .in(IamUser::getId, adminUserIds)
                                .eq(IamUser::getStatus, IamUserStatus.ENABLED)
                );
                if (enabledAdminCount <= 1) {
                    throw lastAdminProtected();
                }
            }

            sessionRevocationPort.revokeAll(request.userId());

            int deletedCount = iamUserRoleMapper.delete(
                    new LambdaQueryWrapper<IamUserRole>()
                            .eq(IamUserRole::getUserId, request.userId())
                            .in(IamUserRole::getRoleId, roleIds)
            );
            if (deletedCount != roleIds.size()) {
                throw rbacConflict();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 清空指定用户的全部角色。
     *
     * <p>无角色时保持幂等；清空后允许用户不再拥有任何角色。若目标是最后一个
     * 启用的 {@code SYSTEM_ADMIN}，则拒绝清空。发生实际删除时只撤销一次会话。</p>
     */
    @Override
    @Transactional
    public void clearAll(long userId) {
        try {
            List<Long> roleIds = iamUserRoleMapper.selectList(
                            new LambdaQueryWrapper<IamUserRole>()
                                    .eq(IamUserRole::getUserId, userId)
                    ).stream()
                    .map(IamUserRole::getRoleId)
                    .distinct()
                    .sorted()
                    .toList();

            Map<Long, IamRole> roleMap = roleIds.isEmpty()
                    ? Map.of()
                    : iamRoleMapper.selectByIdsForUpdate(roleIds).stream()
                            .collect(Collectors.toMap(IamRole::getId, Function.identity()));
            if (roleMap.size() != roleIds.size()) {
                throw roleNotFound();
            }

            IamUser user = iamUserMapper.selectByIdForUpdate(userId);
            if (user == null) {
                throw userNotFound();
            }

            List<IamUserRole> userRoles = iamUserRoleMapper.selectByUserIdForUpdate(userId);
            List<Long> lockedRoleIds = userRoles.stream()
                    .map(IamUserRole::getRoleId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!lockedRoleIds.equals(roleIds)) {
                throw rbacConflict();
            }
            if (roleIds.isEmpty()) {
                return;
            }

            Long systemAdminRoleId = roleIds.stream()
                    .filter(roleId -> SYSTEM_ADMIN.equals(roleMap.get(roleId).getCode()))
                    .findFirst()
                    .orElse(null);
            if (systemAdminRoleId != null && user.getStatus() == IamUserStatus.ENABLED) {
                List<Long> adminUserIds = iamUserRoleMapper.selectList(
                                new LambdaQueryWrapper<IamUserRole>()
                                        .eq(IamUserRole::getRoleId, systemAdminRoleId)
                        ).stream()
                        .map(IamUserRole::getUserId)
                        .toList();
                long enabledAdminCount = iamUserMapper.selectCount(
                        new LambdaQueryWrapper<IamUser>()
                                .in(IamUser::getId, adminUserIds)
                                .eq(IamUser::getStatus, IamUserStatus.ENABLED)
                );
                if (enabledAdminCount <= 1) {
                    throw lastAdminProtected();
                }
            }

            sessionRevocationPort.revokeAll(userId);
            int deletedCount = iamUserRoleMapper.delete(
                    new LambdaQueryWrapper<IamUserRole>()
                            .eq(IamUserRole::getUserId, userId)
            );
            if (deletedCount != roleIds.size()) {
                throw rbacConflict();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 向多个用户批量授予同一个角色。
     *
     * <p>用户 ID 去重并升序处理；重复授权保持幂等。仅对缺少该角色的用户新增
     * 授权，并在写入前分别撤销这些用户的全部会话。</p>
     */
    @Override
    @Transactional
    public List<UserRoleResult> grantUsers(GrantRoleToUsersCommand request) {
        try {
            List<Long> userIds = request.userIds().stream()
                    .distinct()
                    .sorted()
                    .toList();

            IamRole role = iamRoleMapper.selectByIdForUpdate(request.roleId());
            if (role == null) {
                throw roleNotFound();
            }

            Map<Long, IamUser> userMap = iamUserMapper.selectByIdsForUpdate(userIds)
                    .stream()
                    .collect(Collectors.toMap(IamUser::getId, Function.identity()));
            if (userMap.size() != userIds.size()) {
                throw userNotFound();
            }

            Map<Long, IamUserRole> grantsByUserId =
                    iamUserRoleMapper.selectByRoleIdAndUserIdsForUpdate(
                                    request.roleId(),
                                    userIds
                            ).stream()
                            .collect(Collectors.toMap(
                                    IamUserRole::getUserId,
                                    Function.identity()
                            ));

            List<Long> missingUserIds = userIds.stream()
                    .filter(userId -> !grantsByUserId.containsKey(userId))
                    .toList();
            if (!missingUserIds.isEmpty()) {
                long operatorId = currentOperatorPort.currentUserId();
                LocalDateTime grantedAt = LocalDateTime.now(clock);

                for (Long userId : missingUserIds) {
                    sessionRevocationPort.revokeAll(userId);

                    IamUserRole grant = new IamUserRole();
                    grant.setUserId(userId);
                    grant.setRoleId(request.roleId());
                    grant.setGrantedBy(operatorId);
                    grant.setGrantedAt(grantedAt);

                    iamUserRoleMapper.insert(grant);
                    grantsByUserId.put(userId, grant);
                }
            }

            Map<Long, IamRole> roleMap = Map.of(role.getId(), role);
            return userIds.stream()
                    .map(userId -> toResult(
                            grantsByUserId.get(userId),
                            userMap,
                            roleMap
                    ))
                    .toList();
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    private List<UserRoleResult> doGrant(GrantUserRolesCommand request) {
        //1.获取用户角色id列表
        List<Long> roleIds = request.roleIds().stream()
                .distinct()
                .sorted()
                .toList();

        //2.批量查询全部角色
        Map<Long, IamRole> roleMap = iamRoleMapper.selectByIdsForUpdate(roleIds)
                .stream()
                .collect(Collectors.toMap(IamRole::getId, Function.identity()));

        if (roleMap.size() != roleIds.size()) {
            throw roleNotFound();
        }

        //3.查询目标用户
        IamUser user = iamUserMapper.selectByIdForUpdate(request.userId());
        if (user == null) {
            throw userNotFound();
        }

        //4.一次查询请求中已经存在的授权
        Map<Long, IamUserRole> grantsByRoleId =
                iamUserRoleMapper.selectByUserIdForUpdate(request.userId())
                        .stream()
                        .collect(Collectors.toMap(
                                IamUserRole::getRoleId,
                                Function.identity()
                        ));
        //5.只为缺失角色新增授权。所有新增记录使用同一个操作人和时间
        //5.1.找出所有缺失的角色ID
        List<Long> missingRoleIds = roleIds.stream()
                .filter(roleId -> !grantsByRoleId.containsKey(roleId))
                .toList();

        //5.2.如果存在缺失角色，则新增授权
        if (!missingRoleIds.isEmpty()) {
            //5.2.1.找出当前操作人ID
            long operatorId = currentOperatorPort.currentUserId();
            LocalDateTime grantedAt = LocalDateTime.now(clock);

            //5.2.2撤销用户所有会话
            sessionRevocationPort.revokeAll(request.userId());
            //5.2.3.新增授权
            for (Long roleId : missingRoleIds) {
                IamUserRole grant = new IamUserRole();
                grant.setUserId(request.userId());
                grant.setRoleId(roleId);
                grant.setGrantedBy(operatorId);
                grant.setGrantedAt(grantedAt);

                //5.2.4.新增记录
                iamUserRoleMapper.insert(grant);
                grantsByRoleId.put(roleId, grant);
            }

        }

        //6.按 roleId 升序返回全部请求关系
        Map<Long, IamUser> userMap = Map.of(user.getId(), user);

        return roleIds.stream()
                .map(roleId -> toResult(
                        grantsByRoleId.get(roleId),
                        userMap,
                        roleMap))
                .toList();
    }

    private void doRevoke(long userId, long roleId) {
        //1.查询用户、角色是否存在
        IamRole role = iamRoleMapper.selectByIdForUpdate(roleId);
        if (role == null) {
            throw roleNotFound();
        }

        IamUser user = iamUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw userNotFound();
        }

        //2.查询用户的全部角色信息
        List<IamUserRole> userRoles = iamUserRoleMapper.selectByUserIdForUpdate(userId);

        //3.检查目标授权是否存在
        boolean grantExists = userRoles.stream()
                .anyMatch(grant -> grant.getRoleId().equals(roleId));

        if (!grantExists) {
            throw grantNotFound();
        }

        //4.如果撤销的是启用用户的 SYSTEM_ADMIN，统计启用管理员数量
        if (role.getCode().equals(SYSTEM_ADMIN) && user.getStatus() == IamUserStatus.ENABLED) {
           //查询使用该roleID的用户数量
            List<Long> adminUserIds = iamUserRoleMapper.selectList(
                    new LambdaQueryWrapper<IamUserRole>()
                            .eq(IamUserRole::getRoleId, roleId)
            ).stream()
                    .map(IamUserRole::getUserId)
                    .toList();

            //查询启用状态的用户数量
            long enabledAdminCount = iamUserMapper.selectCount(
                    new LambdaQueryWrapper<IamUser>()
                            .in(IamUser::getId, adminUserIds)
                            .eq(IamUser::getStatus, IamUserStatus.ENABLED)
            );

            //如果启用管理员数量小于等于1，则抛出异常
            if (enabledAdminCount <= 1) {
                throw lastAdminProtected();
            }
        }

        //5.先撤销会话，再删除复合主键关系
        sessionRevocationPort.revokeAll(userId);

        iamUserRoleMapper.delete(
                new LambdaQueryWrapper<IamUserRole>()
                        .eq(IamUserRole::getUserId, userId)
                        .eq(IamUserRole::getRoleId, roleId)
        );
    }

    private UserRoleResult toResult(IamUserRole iamUserRole, Map<Long, IamUser> userMap, Map<Long, IamRole> roleMap) {
        return new UserRoleResult(
                iamUserRole.getUserId(),
                userMap.get(iamUserRole.getUserId()).getUsername(),
                iamUserRole.getRoleId(),
                roleMap.get(iamUserRole.getRoleId()).getCode(),
                roleMap.get(iamUserRole.getRoleId()).getName(),
                iamUserRole.getGrantedBy(),
                iamUserRole.getGrantedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private ApiException roleNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND",
                "角色不存在"
        );
    }

    private ApiException userNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "用户不存在"
        );
    }

    private ApiException grantNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GRANT_NOT_FOUND",
                "授权关系不存在"
        );
    }

    private ApiException lastAdminProtected() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED",
                "不能移除最后一个启用管理员的管理员角色"
        );
    }

    private ApiException rbacConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT",
                "RBAC 数据正在被并发修改，请稍后重试"
        );
    }
}
