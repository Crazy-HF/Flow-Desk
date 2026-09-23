package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.command.*;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.result.UserRoleSummaryResult;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.iam.application.service.IamUserService;
import com.flowdesk.iam.application.port.SessionRevocationPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IamUserServiceImpl implements IamUserService {
    //
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    //排序白名单
    private static final Set<String> USER_ORDER_FIELDS =
            Set.of("username", "display_name", "status", "created_at");


    private final IamUserMapper iamUserMapper;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamRoleMapper iamRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentOperatorPort currentOperatorPort;
    private final SessionRevocationPort sessionRevocationPort;
    private final Clock clock;

    public IamUserServiceImpl(IamUserMapper iamUserMapper, IamUserRoleMapper iamUserRoleMapper,
                              IamRoleMapper iamRoleMapper, PasswordEncoder passwordEncoder,
                              CurrentOperatorPort currentOperatorPort, SessionRevocationPort sessionRevocationPort,
                              Clock clock) {
        this.iamUserMapper = iamUserMapper;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamRoleMapper = iamRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.currentOperatorPort = currentOperatorPort;
        this.sessionRevocationPort = sessionRevocationPort;
        this.clock = clock;
    }

    @Override
    public boolean updatePassword(long userId, String encodedPassword) {
        IamUser current = iamUserMapper.selectById(userId);
        if (current == null)
            return false;

        // 用读到的版本做条件更新：并发的其它修改会让这次更新影响 0 行，交由调用方判定冲突
        LambdaUpdateWrapper<IamUser> update = Wrappers.<IamUser>lambdaUpdate()
                .eq(IamUser::getId, userId)
                .eq(IamUser::getVersion, current.getVersion())
                .set(IamUser::getPassword, encodedPassword)
                .set(IamUser::getVersion, current.getVersion() + 1)
                .set(IamUser::getUpdatedAt, LocalDateTime.now(clock));
        // 实体传 null：SET 子句完全由 wrapper 提供
        return iamUserMapper.update(null, update) == 1;
    }

    @Override
    public PageResult<UserResult> page(UserQuery query) {
        Page<IamUser> userPage = new Page<>(query.getCurrent(), query.getSize());

        //构建排序字段
        List<OrderItem> orderItems = query.orderItems(USER_ORDER_FIELDS);
        if (orderItems.isEmpty()) {
            userPage.addOrder(OrderItem.asc("id"));
        } else {
            userPage.addOrder(orderItems);
            userPage.addOrder(OrderItem.asc("id"));
        }

        //根据参数查询用户分页
        //分页查询
        String keyword = StringUtils.hasText(query.getKeyword())
                ? query.getKeyword().trim()
                : null;

        Page<IamUser> result = iamUserMapper.selectUserPage(
                userPage,
                keyword,
                query.getStatus(),
                query.getRoleId()
        );

        if(result.getRecords().isEmpty()){
            return PageResult.from(result, user -> toResult(user, List.of()));
        }


        //分页查询用户角色表
        List<IamUser> users = result.getRecords();
        List<Long> userIds = users.stream().map(IamUser::getId).toList();

        //根据用户Id批量查询user_role表
        List<IamUserRole> userRoles = iamUserRoleMapper.selectList(
                        new LambdaQueryWrapper<IamUserRole>()
                                .in(IamUserRole::getUserId, userIds)
                                .orderByAsc(
                                        IamUserRole::getUserId,
                                        IamUserRole::getRoleId
                                )
                );

        if (userRoles.isEmpty()) {
            return PageResult.from(
                    result,
                    user -> toResult(user, List.of())
            );
        }

        //查询涉及到的RoleIds
        List<Long> roleIds = userRoles.stream()
                .map(IamUserRole::getRoleId)
                .distinct()
                .sorted()
                .toList();

        //查询涉及到的Role
        Map<Long, IamRole> rolesById =
                iamRoleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(
                                IamRole::getId,
                                Function.identity()
                        ));

        //按用户组装 List<UserRoleSummaryResult>。
        Map<Long, List<UserRoleSummaryResult>> rolesByUserId =
                userRoles.stream().collect(Collectors.groupingBy(
                                IamUserRole::getUserId,
                                Collectors.mapping(
                                        userRole -> {
                                            IamRole role = rolesById.get(userRole.getRoleId());

                                            return new UserRoleSummaryResult(
                                                    role.getId(),
                                                    role.getCode(),
                                                    role.getName()
                                            );
                                        },
                                        Collectors.toList()
                                )
                        ));

        return PageResult.from(result,
                user -> toResult(user,
                        rolesByUserId.getOrDefault(user.getId(), List.of())));
    }

    @Override
    public UserResult getById(long userId) {
        //非空判断
        if (userId <= 0) {
            throw userNotFound();
        }

        //查询当前用户是否存在
        IamUser user = iamUserMapper.selectById(userId);
        if (user == null) {
            throw userNotFound();
        }

        //查询当前用户的角色列表
        //1.根据userId查询user_role表
        List<IamUserRole> userRoles = iamUserRoleMapper.selectList(
                new LambdaQueryWrapper<IamUserRole>()
                        .eq(IamUserRole::getUserId, userId)
                        .orderByAsc(
                                IamUserRole::getUserId,
                                IamUserRole::getRoleId
                        )
        );

        if (userRoles.isEmpty()) {
            return toResult(user, List.of());
        }

        //2.根据userRoles获取roleIds
        List<Long> roleIds = userRoles.stream()
                .map(IamUserRole::getRoleId)
                .distinct()
                .sorted()
                .toList();

        //3.根据roleIds查询role表
        Map<Long, IamRole> rolesById =
                iamRoleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(
                                IamRole::getId,
                                Function.identity()
                        ));
        //4.根据userRoles获取角色列表
        List<UserRoleSummaryResult> roles = userRoles.stream().map(userRole -> {
            IamRole role = rolesById.get(userRole.getRoleId());
            return new UserRoleSummaryResult(
                    role.getId(),
                    role.getCode(),
                    role.getName()
            );
        }).toList();
        return toResult(user, roles);
    }

    @Override
    @Transactional
    public UserResult create(CreateUserCommand request) {
        //1.参数处理
        String username = request.username().trim();
        String displayName = request.displayName().trim();

        List<Long> roleIds = request.roleIds() == null
                ? List.of()
                : request.roleIds().stream()
                .distinct()
                .sorted()
                .toList();

        //2.检查用户名是否已存在
        Long existingCount = iamUserMapper.selectCount(
                new LambdaQueryWrapper<IamUser>()
                        .eq(IamUser::getUsername, username)
        );

        if (existingCount > 0) {
            throw usernameConflict();
        }

        //3.密码加密
        /*
         * Argon2id 计算较慢，应在取得数据库悲观锁前完成，
         * 避免编码密码期间长期持有角色锁。
         */
        String encodedPassword = passwordEncoder.encode(request.initialPassword());

        //4.创建角色
        /**
         * 新用户尚不存在，因此先锁角色，再插入用户，最后写授权关系。
         */
        List<IamRole> roles;
        if (roleIds.isEmpty()) {
            roles = List.of();
        } else {
            try {
                //批量查询角色
                roles = iamRoleMapper.selectByIdsForUpdate(roleIds);
            } catch (PessimisticLockingFailureException exception) {
                throw rbacConflict();
            }

            if (roles.size() != roleIds.size()) {
                throw roleNotFound();
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);

        //5.创建用户
        IamUser user = new IamUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPassword(encodedPassword);
        user.setStatus(IamUserStatus.ENABLED);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setVersion(0L);

        //6.插入用户
        try {
            iamUserMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            /*
             * 预检查不能防止两个并发请求同时创建相同用户名，
             * 最终由数据库唯一约束兜底。
             */
            throw usernameConflict();
        }

        //7.插入user_role表
        if (!roles.isEmpty()) {
            //获取当前用户id(操作人)
            long operatorId = currentOperatorPort.currentUserId();

            for (IamRole role : roles) {
                IamUserRole grant = new IamUserRole();
                grant.setUserId(user.getId());
                grant.setRoleId(role.getId());
                grant.setGrantedBy(operatorId);
                grant.setGrantedAt(now);

                iamUserRoleMapper.insert(grant);
            }
        }

        //8.组装结果
        List<UserRoleSummaryResult> roleSummaries = roles.stream()
                .map(role -> new UserRoleSummaryResult(
                        role.getId(),
                        role.getCode(),
                        role.getName()
                ))
                .toList();

        return toResult(user, roleSummaries);
    }

    /**
     * 更新用户信息
     */
    @Override
    @Transactional
    public UserResult update(long userId, UpdateUserCommand request){
        if (userId <= 0) {
            throw userNotFound();
        }

        String displayName = request.displayName().trim();
        LocalDateTime now = LocalDateTime.now(clock);

        // 更新用户信息
        int affectedRows = iamUserMapper.update(
                null,
                Wrappers.<IamUser>lambdaUpdate()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.version())
                        .set(IamUser::getDisplayName, displayName)
                        .set(IamUser::getUpdatedAt, now)
                        .set(IamUser::getVersion, request.version() + 1)
        );

        // 检查更新结果
        if (affectedRows != 1) {
            if (iamUserMapper.selectById(userId) == null) {
                throw userNotFound();
            }
            throw userConflict();
        }

        return getById(userId);
    }

    /**
     * 启用用户
     */
    @Override
    @Transactional
    public UserResult enable(long userId, UserStatusChangeCommand request) {
        if (userId <= 0) {
            throw userNotFound();
        }

        IamUser current = iamUserMapper.selectById(userId);
        if (current == null) {
            throw userNotFound();
        }

        if (!request.version().equals(current.getVersion())) {
            throw userConflict();
        }

        // 已经启用：幂等成功，不修改版本，也不撤销会话。
        if (current.getStatus() == IamUserStatus.ENABLED) {
            return getById(userId);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        int affectedRows = iamUserMapper.update(
                null,
                Wrappers.<IamUser>lambdaUpdate()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.version())
                        .eq(IamUser::getStatus, IamUserStatus.DISABLED)
                        .set(IamUser::getStatus, IamUserStatus.ENABLED)
                        .set(IamUser::getUpdatedAt, now)
                        .set(IamUser::getVersion, request.version() + 1)
        );

        if (affectedRows != 1) {
            throw userConflict();
        }

        // 状态实际发生变化，提交数据库事务前撤销全部旧会话。
        sessionRevocationPort.revokeAll(userId);

        return getById(userId);
    }

    /**
     * 停用用户。
     *
     * <p>锁顺序：先锁 {@code SYSTEM_ADMIN} 角色（“最后启用管理员”不变量的串行化锁），
     * 再锁目标用户；状态实际变化时先撤销该用户全部会话，再提交 MySQL 事务。</p>
     */
    @Override
    @Transactional
    public UserResult disable(long userId, UserStatusChangeCommand request) {
        if (userId <= 0) {
            throw userNotFound();
        }

        //1.先锁角色(先锁 SYSTEM_ADMIN 再锁用户)
        IamRole systemAdminRole = iamRoleMapper.selectByCodeForUpdate(SYSTEM_ADMIN);
        Long systemAdminRoleId = systemAdminRole == null ? null : systemAdminRole.getId();

        //2.再锁用户
        IamUser user = iamUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw userNotFound();
        }

        //3.版本校验（与 enable 一致：版本校验放在幂等短路之前）
        if (!request.version().equals(user.getVersion())) {
            throw userConflict();
        }

        //4.幂等校验（已经停用就直接返回，不改版本、不撤会话）
        if (user.getStatus() == IamUserStatus.DISABLED) {
            return getById(userId);
        }

        //5.保护：不允许停用最后一个启用管理员。
        //  code 创建后不可修改，所以直接用第 1 步由 code 取得的角色 id 比对关系表，
        //  不必为用户的每个角色再回查一次编码；不得写死数字 id。
        if (systemAdminRoleId != null
                && holdsRole(userId, systemAdminRoleId)
                && countEnabledHolders(systemAdminRoleId) <= 1) {
            throw lastAdminProtected();
        }

        //TODO 工单模块未实现：目标用户仍负责活动工单时，必须先完成管理性交接
        //（handoffReason + handoffs，见 docs/api-design.md 8.3）并在同一事务内替换负责人；
        //当前无工单依赖，直接停用。

        //6.条件更新：id + version + status=ENABLED 三重条件，并发下只有一个能成功
        LocalDateTime now = LocalDateTime.now(clock);
        int affectedRows = iamUserMapper.update(
                null,
                Wrappers.<IamUser>lambdaUpdate()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.version())
                        .eq(IamUser::getStatus, IamUserStatus.ENABLED)
                        .set(IamUser::getStatus, IamUserStatus.DISABLED)
                        .set(IamUser::getUpdatedAt, now)
                        .set(IamUser::getVersion, request.version() + 1)
        );
        if (affectedRows != 1) {
            throw userConflict();
        }

        //7.状态实际变化：提交 MySQL 事务前撤销该用户全部旧会话
        sessionRevocationPort.revokeAll(userId);

        return getById(userId);
    }

    /**
     * 替换用户角色（PUT 语义：请求体是完整角色集合，不是增量）。
     *
     * <p>锁顺序：目标角色与 {@code SYSTEM_ADMIN} 角色一起按 id 升序加锁 → 目标用户 →
     * 该用户现有授权关系，与 {@code docs/modules/rbac.md} 第 7 节的固定顺序一致。
     * 集合实际变化时，先撤销该用户全部会话，再提交 MySQL 事务。</p>
     */
    @Override
    @Transactional
    public UserResult replaceRoles(long userId, ReplaceUserRolesCommand request) {
        try {
            return doReplaceRoles(userId, request);
        } catch (PessimisticLockingFailureException exception) {
            throw rbacConflict();
        }
    }

    /**
     * 重置用户密码
     */
    @Override
    @Transactional
    public void resetPassword(long userId, ResetUserPasswordCommand request) {

        if (userId <= 0) {
            throw userNotFound();
        }

        /*
         * 密码编码放在数据库写操作之前。
         * Argon2id 计算较慢，不应占用数据库事务中的锁等待时间。
         */
        String encodedPassword =
                passwordEncoder.encode(request.newPassword());

        LocalDateTime now = LocalDateTime.now(clock);

        int affectedRows = iamUserMapper.update(
                null,
                Wrappers.<IamUser>lambdaUpdate()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.version())
                        .set(IamUser::getPassword, encodedPassword)
                        .set(IamUser::getUpdatedAt, now)
                        .set(IamUser::getVersion, request.version() + 1)
        );

        if (affectedRows != 1) {
            if (iamUserMapper.selectById(userId) == null) {
                throw userNotFound();
            }

            throw userConflict();
        }

        /*
         * 在 MySQL 事务提交前撤销目标用户的全部会话。
         *
         * Redis 撤销失败会抛出异常，使当前 MySQL 事务回滚；
         * Redis 撤销成功后，旧 Access Token 和 Refresh Token
         * 均不能继续使用。
         */
        sessionRevocationPort.revokeAll(userId);
    }

    private UserResult doReplaceRoles(long userId, ReplaceUserRolesCommand request) {
        if (userId <= 0) {
            throw userNotFound();
        }

        //1.归一化目标角色集合：去重 + 升序，null 按空集合处理
        List<Long> targetRoleIds = request.roleIds() == null
                ? List.of()
                : request.roleIds().stream().distinct().sorted().toList();

        /*
         * 2.解析 SYSTEM_ADMIN 的角色 id，并把它一并放进锁定集合。
         *
         * 必须无条件锁它：待删除的角色可能正是 SYSTEM_ADMIN，而它不在 targetRoleIds 里，
         * 只锁"请求里的角色"会让"最后启用管理员"的计数失去串行化保护。
         * id 不可变、code 创建后不可修改，所以这次无锁读取只用于发现 id，真正的互斥来自下面的加锁语句。
         */
        IamRole systemAdmin = iamRoleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery().eq(IamRole::getCode, SYSTEM_ADMIN));
        Long systemAdminRoleId = systemAdmin == null ? null : systemAdmin.getId();

        //3.角色层一次加锁，按 id 升序，避免与其他写者形成反向加锁
        List<Long> lockRoleIds = new ArrayList<>(targetRoleIds);
        if (systemAdminRoleId != null && !lockRoleIds.contains(systemAdminRoleId)) {
            lockRoleIds.add(systemAdminRoleId);
        }
        lockRoleIds = lockRoleIds.stream().distinct().sorted().toList();

        List<IamRole> lockedRoles = lockRoleIds.isEmpty()
                ? List.of()
                : iamRoleMapper.selectByIdsForUpdate(lockRoleIds);

        //4.请求里的角色必须全部存在，否则整单失败，不产生部分写入
        Set<Long> lockedRoleIds = lockedRoles.stream()
                .map(IamRole::getId)
                .collect(Collectors.toSet());

        if (!lockedRoleIds.containsAll(targetRoleIds)) {
            throw roleNotFound();
        }

        //5.再锁用户
        IamUser user = iamUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw userNotFound();
        }

        //6.再锁该用户现有授权关系（按 role_id 升序）
        List<IamUserRole> currentGrants = iamUserRoleMapper.selectByUserIdForUpdate(userId);
        Set<Long> currentRoleIds = currentGrants.stream()
                .map(IamUserRole::getRoleId)
                .collect(Collectors.toSet());

        //7.版本校验
        if (!request.version().equals(user.getVersion())) {
            throw userConflict();
        }

        //8.差集：请求里有库里没有 → 新增；库里有请求里没有 → 删除；两边都有 → 保持原审计字段不动
        List<Long> toAdd = targetRoleIds.stream()
                .filter(roleId -> !currentRoleIds.contains(roleId))
                .toList();

        List<Long> toRemove = currentRoleIds.stream()
                .filter(roleId -> !targetRoleIds.contains(roleId))
                .sorted()
                .toList();

        //9.幂等：集合没有实际变化时直接返回，不改版本、不撤会话
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return getById(userId);
        }

        //10.保护：不允许移除最后一个启用管理员的 SYSTEM_ADMIN 角色
        if (systemAdminRoleId != null
                && toRemove.contains(systemAdminRoleId)
                && user.getStatus() == IamUserStatus.ENABLED
                && countEnabledHolders(systemAdminRoleId) <= 1) {
            throw lastAdminProtected();
        }

        //TODO 工单模块未实现：若 toRemove 涉及 IT_SUPPORT 且该用户仍负责活动工单，
        //必须先完成管理性交接（handoffReason + handoffs，见 docs/api-design.md 8.3）
        //并在同一事务内替换负责人、写入交接记录；当前无工单依赖，直接替换。

        LocalDateTime now = LocalDateTime.now(clock);

        //11.版本条件更新：id + version 双重条件，并发下只有一个能成功
        int affectedRows = iamUserMapper.update(
                null,
                Wrappers.<IamUser>lambdaUpdate()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.version())
                        .set(IamUser::getUpdatedAt, now)
                        .set(IamUser::getVersion, request.version() + 1)
        );
        if (affectedRows != 1) {
            throw userConflict();
        }

        //12.删除被移除的关系；行数与预期不符说明锁期间的读已不可信
        if (!toRemove.isEmpty()) {
            int deleted = iamUserRoleMapper.delete(
                    Wrappers.<IamUserRole>lambdaQuery()
                            .eq(IamUserRole::getUserId, userId)
                            .in(IamUserRole::getRoleId, toRemove)
            );
            if (deleted != toRemove.size()) {
                throw rbacConflict();
            }
        }

        //13.新增缺失的关系，审计两列必须写入
        if (!toAdd.isEmpty()) {
            long operatorId = currentOperatorPort.currentUserId();
            for (Long roleId : toAdd) {
                IamUserRole grant = new IamUserRole();
                grant.setUserId(userId);
                grant.setRoleId(roleId);
                grant.setGrantedBy(operatorId);
                grant.setGrantedAt(now);
                iamUserRoleMapper.insert(grant);
            }
        }

        //14.集合实际变化：提交 MySQL 事务前撤销该用户全部旧会话，只执行一次
        sessionRevocationPort.revokeAll(userId);

        return getById(userId);
    }

    private UserResult toResult(IamUser user, List<UserRoleSummaryResult> roles) {
        return new UserResult(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getStatus(),
                roles,
                toOffsetDateTime(user.getCreatedAt()),
                toOffsetDateTime(user.getUpdatedAt()),
                user.getVersion()
        );
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null
                ? null
                : value.atOffset(ZoneOffset.UTC);
    }

    /** 目标用户是否持有该角色；调用方必须已持有相关角色行的排他锁。 */
    private boolean holdsRole(long userId, long roleId) {
        return iamUserRoleMapper.selectCount(
                Wrappers.<IamUserRole>lambdaQuery()
                        .eq(IamUserRole::getUserId, userId)
                        .eq(IamUserRole::getRoleId, roleId)
        ) > 0;
    }

    /**
     * 统计持有该角色且账号启用的用户数。
     *
     * <p>调用方必须已持有该角色行的排他锁，否则计数会被并发的授权/启停写操作改变。</p>
     */
    private long countEnabledHolders(long roleId) {
        List<Long> holderIds = iamUserRoleMapper.selectList(
                        Wrappers.<IamUserRole>lambdaQuery()
                                .eq(IamUserRole::getRoleId, roleId)
                                .select(IamUserRole::getUserId))
                .stream()
                .map(IamUserRole::getUserId)
                .toList();

        if (holderIds.isEmpty()) {
            return 0;
        }

        return iamUserMapper.selectCount(
                Wrappers.<IamUser>lambdaQuery()
                        .in(IamUser::getId, holderIds)
                        .eq(IamUser::getStatus, IamUserStatus.ENABLED)
        );
    }

    private ApiException userNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "用户不存在"
        );
    }

    private ApiException usernameConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "USERNAME_CONFLICT",
                "登录名已存在"
        );
    }

    private ApiException roleNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND",
                "角色不存在"
        );
    }

    private ApiException rbacConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT",
                "RBAC 数据正在被并发修改，请稍后重试"
        );
    }

    private ApiException userConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "USER_CONFLICT",
                "用户信息已发生变化，请刷新后重试"
        );
    }

    private ApiException lastAdminProtected() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED",
                "不能停用最后一个启用管理员"
        );
    }
}
