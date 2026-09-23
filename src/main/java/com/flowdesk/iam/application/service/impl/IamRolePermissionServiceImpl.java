package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.iam.application.service.IamRolePermissionService;
import com.flowdesk.iam.application.port.SessionRevocationPort;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class IamRolePermissionServiceImpl implements IamRolePermissionService {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String RBAC_MANAGE = "RBAC_MANAGE";
    // 排序字段
    private static final Set<String> ORDER_FIELDS =
            Set.of("role_id", "permission_id");

    private final Clock clock;
    private final CurrentOperatorPort currentOperatorPort;
    private final SessionRevocationPort sessionRevocationPort;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamRolePermissionMapper iamRolePermissionMapper;
    private final IamRoleMapper iamRoleMapper;
    private final IamPermissionMapper iamPermissionMapper;

    public IamRolePermissionServiceImpl(
            Clock clock,
            CurrentOperatorPort currentOperatorPort,
            SessionRevocationPort sessionRevocationPort,
            IamUserRoleMapper iamUserRoleMapper,
            IamRolePermissionMapper iamRolePermissionMapper,
            IamRoleMapper iamRoleMapper,
            IamPermissionMapper iamPermissionMapper
    ) {
        this.clock = clock;
        this.currentOperatorPort = currentOperatorPort;
        this.sessionRevocationPort = sessionRevocationPort;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamRolePermissionMapper = iamRolePermissionMapper;
        this.iamRoleMapper = iamRoleMapper;
        this.iamPermissionMapper = iamPermissionMapper;
    }

    /**
     * 分页查询角色权限
     * @param query
     * @return
     */
    @Override
    public PageResult<RolePermissionResult> page(RolePermissionQuery query) {
        //1.创建分页对象
        Page<IamRolePermission> page = new Page<>(query.getCurrent(), query.getPageSize());

        //构建排序字段
        /**
         * 只允许 role_id、permission_id 排序。
         * 没有指定排序时，默认按 role_id ASC, permission_id ASC。*/
        List<OrderItem> orderItems = query.orderItems(ORDER_FIELDS);

        if (orderItems.isEmpty()) {
            page.addOrder(
                    OrderItem.asc("role_id"),
                    OrderItem.asc("permission_id")
            );
        } else {
            page.addOrder(orderItems);
        }

        //3.根据Id创建查询参数
        LambdaQueryWrapper<IamRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(query.getRoleId() != null, IamRolePermission::getRoleId, query.getRoleId());
        wrapper.eq(query.getPermissionId() != null, IamRolePermission::getPermissionId, query.getPermissionId());
        //4.执行分页查询
        Page<IamRolePermission> resultPage = iamRolePermissionMapper.selectPage(page, wrapper);

        if(resultPage.getRecords().isEmpty()){
            return PageResult.from(resultPage,
rolePermission ->
                toResult(rolePermission, Map.of(), Map.of())
            );
        }

        //5.获取角色Id,权限Id
        Set<Long> roleIds = resultPage.getRecords().stream().map(IamRolePermission::getRoleId).collect(Collectors.toSet());
        Set<Long> permissionIds = resultPage.getRecords().stream().map(IamRolePermission::getPermissionId).collect(Collectors.toSet());

        //构建BO参数
        Map<Long, IamRole> roleMap =
                iamRoleMapper.selectBatchIds(roleIds)
                        .stream()
                        .collect(
                                Collectors.toMap(IamRole::getId, Function.identity()));
        Map<Long, IamPermission> permissionMap =
                iamPermissionMapper.selectBatchIds(permissionIds)
                        .stream()
                        .collect(
                                Collectors.toMap(IamPermission::getId, Function.identity()));

        return PageResult.from(resultPage,
                rolePermission ->
                        toResult(rolePermission, roleMap, permissionMap)
        );
    }

    /**
     * 授权角色权限
     * @param request
     * @return
     */
    @Override
    @Transactional
    public List<RolePermissionResult> grant(GrantRolePermissionsCommand request) {
        try {
            return doGrant(request);
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 撤销角色权限
     * @param roleId
     * @param permissionId
     */
    @Override
    @Transactional
    public void revoke(long roleId, long permissionId) {
        try {
            doRevoke(roleId, permissionId);
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 批量撤销一个角色的多个权限。
     *
     * <p>任一权限或授权关系不存在时整批失败；禁止撤销 {@code SYSTEM_ADMIN}
     * 的 {@code RBAC_MANAGE}。发生实际删除前，每个受影响用户只撤销一次会话。</p>
     */
    @Override
    @Transactional
    public void revokeBatch(RevokeRolePermissionsCommand request) {
        try {
            List<Long> permissionIds = request.permissionIds().stream()
                    .distinct()
                    .sorted()
                    .toList();

            IamRole role = iamRoleMapper.selectByIdForUpdate(request.roleId());
            if (role == null) {
                throw roleNotFound();
            }

            Map<Long, IamPermission> permissionMap =
                    iamPermissionMapper.selectByIdsForUpdate(permissionIds)
                            .stream()
                            .collect(Collectors.toMap(
                                    IamPermission::getId,
                                    Function.identity()
                            ));
            if (permissionMap.size() != permissionIds.size()) {
                throw permissionNotFound();
            }

            List<IamUserRole> affectedUsers =
                    iamUserRoleMapper.selectByRoleIdForUpdate(request.roleId());

            Map<Long, IamRolePermission> grantsByPermissionId =
                    iamRolePermissionMapper.selectByRoleIdForUpdate(request.roleId())
                            .stream()
                            .collect(Collectors.toMap(
                                    IamRolePermission::getPermissionId,
                                    Function.identity()
                            ));
            boolean missingGrant = permissionIds.stream()
                    .anyMatch(permissionId -> !grantsByPermissionId.containsKey(permissionId));
            if (missingGrant) {
                throw grantNotFound();
            }

            boolean removesRbacManage = permissionIds.stream()
                    .map(permissionMap::get)
                    .anyMatch(permission -> RBAC_MANAGE.equals(permission.getCode()));
            if (SYSTEM_ADMIN.equals(role.getCode()) && removesRbacManage) {
                throw protectedGrant();
            }

            for (IamUserRole affectedUser : affectedUsers) {
                sessionRevocationPort.revokeAll(affectedUser.getUserId());
            }

            int deletedCount = iamRolePermissionMapper.delete(
                    new LambdaQueryWrapper<IamRolePermission>()
                            .eq(IamRolePermission::getRoleId, request.roleId())
                            .in(IamRolePermission::getPermissionId, permissionIds)
            );
            if (deletedCount != permissionIds.size()) {
                throw rbacConflict();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 清空指定角色的全部权限。
     *
     * <p>无权限时保持幂等；禁止清空仍包含 {@code RBAC_MANAGE} 的
     * {@code SYSTEM_ADMIN}。发生实际删除前，每个受影响用户只撤销一次会话。</p>
     */
    @Override
    @Transactional
    public void clearAll(long roleId) {
        try {
            IamRole role = iamRoleMapper.selectByIdForUpdate(roleId);
            if (role == null) {
                throw roleNotFound();
            }

            List<Long> permissionIds = iamRolePermissionMapper.selectList(
                            new LambdaQueryWrapper<IamRolePermission>()
                                    .eq(IamRolePermission::getRoleId, roleId)
                                    .orderByAsc(IamRolePermission::getPermissionId)
                    ).stream()
                    .map(IamRolePermission::getPermissionId)
                    .distinct()
                    .toList();
            if (permissionIds.isEmpty()) {
                return;
            }

            Map<Long, IamPermission> permissionMap =
                    iamPermissionMapper.selectByIdsForUpdate(permissionIds)
                            .stream()
                            .collect(Collectors.toMap(
                                    IamPermission::getId,
                                    Function.identity()
                            ));
            if (permissionMap.size() != permissionIds.size()) {
                throw permissionNotFound();
            }

            boolean containsRbacManage = permissionIds.stream()
                    .map(permissionMap::get)
                    .anyMatch(permission -> RBAC_MANAGE.equals(permission.getCode()));
            if (SYSTEM_ADMIN.equals(role.getCode()) && containsRbacManage) {
                throw protectedGrant();
            }

            List<IamUserRole> affectedUsers =
                    iamUserRoleMapper.selectByRoleIdForUpdate(roleId);

            List<Long> lockedPermissionIds =
                    iamRolePermissionMapper.selectByRoleIdForUpdate(roleId)
                            .stream()
                            .map(IamRolePermission::getPermissionId)
                            .distinct()
                            .toList();
            if (!lockedPermissionIds.equals(permissionIds)) {
                throw rbacConflict();
            }

            for (IamUserRole affectedUser : affectedUsers) {
                sessionRevocationPort.revokeAll(affectedUser.getUserId());
            }

            int deletedCount = iamRolePermissionMapper.delete(
                    new LambdaQueryWrapper<IamRolePermission>()
                            .eq(IamRolePermission::getRoleId, roleId)
            );
            if (deletedCount != permissionIds.size()) {
                throw rbacConflict();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    private List<RolePermissionResult> doGrant(GrantRolePermissionsCommand request) {
        List<Long> permissionIds = request.permissionIds().stream()
                .distinct()
                .sorted()
                .toList();

        // 1. 角色
        IamRole role = iamRoleMapper.selectByIdForUpdate(request.roleId());
        if (role == null) {
            throw roleNotFound();
        }

        // 2. 权限，同层按主键升序加锁
        Map<Long, IamPermission> permissionMap =
                iamPermissionMapper.selectByIdsForUpdate(permissionIds)
                        .stream()
                        .collect(Collectors.toMap(
                                IamPermission::getId,
                                Function.identity()
                        ));
        if (permissionMap.size() != permissionIds.size()) {
            throw permissionNotFound();
        }

        // 3. 按 user_id 升序锁定并取得受影响用户快照
        List<IamUserRole> affectedUsers =
                iamUserRoleMapper.selectByRoleIdForUpdate(request.roleId());

        // 4. 按 permission_id 升序锁定该角色的现有授权关系
        Map<Long, IamRolePermission> grantsByPermissionId =
                iamRolePermissionMapper.selectByRoleIdForUpdate(request.roleId())
                        .stream()
                        .collect(Collectors.toMap(
                                IamRolePermission::getPermissionId,
                                Function.identity()
                        ));

        List<Long> missingPermissionIds = permissionIds.stream()
                .filter(permissionId -> !grantsByPermissionId.containsKey(permissionId))
                .toList();

        // 5. 全部已存在时保持幂等：不取操作人、不撤销会话、不写库
        if (!missingPermissionIds.isEmpty()) {
            long operatorId = currentOperatorPort.currentUserId();
            LocalDateTime grantedAt = LocalDateTime.now(clock);

            // 6. 实际变化前，按快照顺序撤销该角色全部用户会话
            for (IamUserRole affectedUser : affectedUsers) {
                sessionRevocationPort.revokeAll(affectedUser.getUserId());
            }

            // 7. 只新增缺失授权，所有新增记录共享操作人和授权时间
            for (Long permissionId : missingPermissionIds) {
                IamRolePermission grant = new IamRolePermission();
                grant.setRoleId(request.roleId());
                grant.setPermissionId(permissionId);
                grant.setGrantedBy(operatorId);
                grant.setGrantedAt(grantedAt);

                iamRolePermissionMapper.insert(grant);
                grantsByPermissionId.put(permissionId, grant);
            }
        }

        Map<Long, IamRole> roleMap = Map.of(role.getId(), role);
        return permissionIds.stream()
                .map(permissionId -> toResult(
                        grantsByPermissionId.get(permissionId),
                        roleMap,
                        permissionMap
                ))
                .toList();
    }

    private void doRevoke(long roleId, long permissionId) {
        // 1. 角色
        IamRole role = iamRoleMapper.selectByIdForUpdate(roleId);
        if (role == null) {
            throw roleNotFound();
        }

        // 2. 权限
        IamPermission permission =
                iamPermissionMapper.selectByIdForUpdate(permissionId);
        if (permission == null) {
            throw permissionNotFound();
        }

        // 3. 本次变更的受影响用户集合"的快照
        List<IamUserRole> affectedUsers =
                iamUserRoleMapper.selectByRoleIdForUpdate(roleId);

        // 4. 锁定并重新读取目标授权关系
        IamRolePermission grant =
                iamRolePermissionMapper.selectByKeyForUpdate(
                        roleId,
                        permissionId
                );

        if (grant == null) {
            throw grantNotFound();
        }

        // 5. 保护 SYSTEM_ADMIN 的 RBAC_MANAGE 授权
        if (SYSTEM_ADMIN.equals(role.getCode())
                && RBAC_MANAGE.equals(permission.getCode())) {
            throw protectedGrant();
        }

        // 6. 先撤销该角色全部用户会话
        for (IamUserRole affectedUser : affectedUsers) {
            sessionRevocationPort.revokeAll(affectedUser.getUserId());
        }

        // 7. 再删除复合主键关系
        iamRolePermissionMapper.delete(
                new LambdaQueryWrapper<IamRolePermission>()
                        .eq(IamRolePermission::getRoleId, roleId)
                        .eq(IamRolePermission::getPermissionId, permissionId)
        );
    }

    /**转换为服务结果*/
    private RolePermissionResult toResult(
            IamRolePermission grant,
            Map<Long, IamRole> roleMap,
            Map<Long, IamPermission> permissionMap
    ) {
        IamRole role = roleMap.get(grant.getRoleId());
        IamPermission permission = permissionMap.get(grant.getPermissionId());

        return new RolePermissionResult(
                grant.getRoleId(),
                role.getCode(),
                grant.getPermissionId(),
                permission.getCode(),
                permission.getName(),
                grant.getGrantedBy(),
                grant.getGrantedAt() == null
                        ? null
                        : grant.getGrantedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private ApiException roleNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND",
                "角色不存在"
        );
    }

    private ApiException permissionNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "PERMISSION_NOT_FOUND",
                "权限不存在"
        );
    }

    private ApiException grantNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GRANT_NOT_FOUND",
                "授权关系不存在"
        );
    }

    private ApiException protectedGrant() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT",
                "不能撤销系统管理员的 RBAC 管理权限"
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
