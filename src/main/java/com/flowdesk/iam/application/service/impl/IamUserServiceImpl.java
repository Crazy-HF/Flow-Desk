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
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.result.UserRoleSummaryResult;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.AdminHandoffPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IamUserServiceImpl implements IamUserService {
    //
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    /** IT 支持人员角色编码：只有"这次替换把它摘掉"才触发管理性交接（docs/api-design.md 8.3）。 */
    private static final String IT_SUPPORT = "IT_SUPPORT";
    /** 管理性交接权限编码：仅在实际发生交接时要求（V2 已授予 SYSTEM_ADMIN）。 */
    private static final String TICKET_ADMIN_HANDOFF = "TICKET_ADMIN_HANDOFF";
    /** 交接方案里同一工单只能出现一次；重复是纯请求缺陷，不依赖数据库状态。 */
    private static final String HANDOFF_DUPLICATE_TICKET = "同一工单不能在交接方案里重复出现";
    //排序白名单
    private static final Set<String> USER_ORDER_FIELDS =
            Set.of("username", "display_name", "status", "created_at");


    private final IamUserMapper iamUserMapper;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamRoleMapper iamRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentOperatorPort currentOperatorPort;
    private final SessionRevocationPort sessionRevocationPort;
    private final AdminHandoffPort adminHandoffPort;
    private final Clock clock;

    public IamUserServiceImpl(IamUserMapper iamUserMapper, IamUserRoleMapper iamUserRoleMapper,
                              IamRoleMapper iamRoleMapper, PasswordEncoder passwordEncoder,
                              CurrentOperatorPort currentOperatorPort, SessionRevocationPort sessionRevocationPort,
                              AdminHandoffPort adminHandoffPort, Clock clock) {
        this.iamUserMapper = iamUserMapper;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamRoleMapper = iamRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.currentOperatorPort = currentOperatorPort;
        this.sessionRevocationPort = sessionRevocationPort;
        this.adminHandoffPort = adminHandoffPort;
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
     * <p>锁顺序：{@code SYSTEM_ADMIN} 角色（“最后启用管理员”不变量的串行化锁）→ 交接涉及的工单行 →
     * 相关用户行 → 目标用户行。目标用户行<strong>必须排在工单行之后</strong>：工单模块所有动作都以工单行
     * 写锁起手，反序会与 {@code ticket_record} 外键申请的共享锁首尾相接成环（见
     * {@code TicketServiceImpl#transfer} 的注释）。</p>
     *
     * <p>顺序：全部 MySQL 写（交接 + 停用）→ 撤销 Redis 会话 → 提交。Redis 撤销失败会抛出异常让
     * 整个 MySQL 事务回滚，因此不会出现"账号已变更却返回会话撤销成功"。</p>
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

        /*
         * 2.无锁预读：只用来决定"这次停用要不要走交接分支"。
         *   它读的是本事务的一致性快照，可能已经过时，因此任何最终判定都留到拿到用户行锁之后
         *   （docs/modules/rbac.md 第 7 节：取得锁后必须重新读取并校验不变量）。
         */
        IamUser pre = iamUserMapper.selectById(userId);

        //3.幂等：快照显示已停用且版本一致 → 锁用户行复核后原样返回（无状态变化，不要求交接、不撤会话）
        if (pre != null
                && pre.getStatus() == IamUserStatus.DISABLED
                && request.version().equals(pre.getVersion())) {
            IamUser locked = iamUserMapper.selectByIdForUpdate(userId);
            if (locked == null) {
                throw userNotFound();
            }
            if (!request.version().equals(locked.getVersion())) {
                throw userConflict();
            }
            return getById(userId);
        }

        /*
         * 4.交接分支：账号停用后他不能再处理任何工单，因此仍负责活动工单时必须先完成管理性交接。
         *   预读为 null 只有一种含义——该账号是在本事务快照建立之后才创建的，而负责人只能由
         *   领取、转交或上一次交接产生，三者都要求用户先存在，所以它不可能已经负责任何工单；
         *   真正的不存在由下面的加锁读取按 404 处理。
         */
        boolean handoffDone = false;
        if (pre != null && pre.getStatus() == IamUserStatus.ENABLED) {
            Map<String, AdminHandoffInstruction> plan = normalizeHandoffs(request.handoffs());
            List<AdminHandoffAssignmentResult> assignments =
                    adminHandoffPort.findActiveAssignments(userId);
            validateHandoffPlan(assignments, request.handoffReason(), plan);
            if (!plan.isEmpty()) {
                handoffDone = replaceAssignees(userId, request.handoffReason(), plan);
            }
        }

        //5.交接之后才锁目标用户行，并复核版本
        IamUser user = iamUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw userNotFound();
        }
        if (!request.version().equals(user.getVersion())) {
            throw userConflict();
        }

        //6.保护：不允许停用最后一个启用管理员。
        //  code 创建后不可修改，所以直接用第 1 步由 code 取得的角色 id 比对关系表，
        //  不必为用户的每个角色再回查一次编码；不得写死数字 id。
        if (systemAdminRoleId != null
                && holdsRole(userId, systemAdminRoleId)
                && countEnabledHolders(systemAdminRoleId) <= 1) {
            throw lastAdminProtected();
        }

        /*
         * 7.写库前的最后一道不变量：即将停用的账号不得再留有活动工单。
         *   第 4 步用的是无锁快照，并发转交/交接可能让"当时没有活动工单"失效，
         *   这里按最终状态再确认一次，避免账号已停用却留下无效负责人。
         */
        if (user.getStatus() == IamUserStatus.ENABLED) {
            requireNoActiveAssignmentsLeft(userId, handoffDone);
        }

        //8.条件更新：id + version + status=ENABLED 三重条件，并发下只有一个能成功
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

        //9.状态实际变化：提交 MySQL 事务前撤销该用户全部旧会话
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

    /**
     * 查询目标用户当前负责的活动工单最小元数据（管理性交接读取侧，{@code docs/api-design.md} 8.3）。
     *
     * <p>只读、不加锁：读的是管理页组织交接方案时看到的那份信息，真正执行交接时由
     * {@link AdminHandoffPort#replaceAssignees} 在锁内重新判定。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminHandoffAssignmentResult> activeTicketAssignments(long userId) {
        if (userId <= 0) {
            throw userNotFound();
        }

        if (iamUserMapper.selectById(userId) == null) {
            throw userNotFound();
        }

        return adminHandoffPort.findActiveAssignments(userId);
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
         * 2.解析两个内置角色的 id：SYSTEM_ADMIN 用于"最后启用管理员"不变量，
         *  IT_SUPPORT 用于判断这次替换是否需要管理性交接（docs/api-design.md 8.3）。
         *
         * 必须无条件锁它们：待删除的角色可能正是 SYSTEM_ADMIN，而它不在 targetRoleIds 里，
         * 只锁"请求里的角色"会让"最后启用管理员"的计数失去串行化保护。
         * id 不可变、code 创建后不可修改，所以这两次无锁读取只用于发现 id，真正的互斥来自下面的加锁语句。
         */
        IamRole systemAdmin = iamRoleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery().eq(IamRole::getCode, SYSTEM_ADMIN));
        Long systemAdminRoleId = systemAdmin == null ? null : systemAdmin.getId();

        IamRole itSupport = iamRoleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery().eq(IamRole::getCode, IT_SUPPORT));
        Long itSupportRoleId = itSupport == null ? null : itSupport.getId();

        //3.角色层一次加锁，按 id 升序，避免与其他写者形成反向加锁
        List<Long> lockRoleIds = new ArrayList<>(targetRoleIds);
        if (systemAdminRoleId != null && !lockRoleIds.contains(systemAdminRoleId)) {
            lockRoleIds.add(systemAdminRoleId);
        }
        if (itSupportRoleId != null && !lockRoleIds.contains(itSupportRoleId)) {
            lockRoleIds.add(itSupportRoleId);
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

        /*
         * 5.无锁预读该用户现有授权关系：只用来判断"这次替换会不会摘掉 IT_SUPPORT"，
         *   从而决定要不要先做管理性交接。授权关系的权威读取仍在第 9 步加锁之后。
         */
        Set<Long> preRoleIds = iamUserRoleMapper.selectList(
                        Wrappers.<IamUserRole>lambdaQuery()
                                .eq(IamUserRole::getUserId, userId)
                                .select(IamUserRole::getRoleId))
                .stream()
                .map(IamUserRole::getRoleId)
                .collect(Collectors.toSet());

        boolean preRemovesItSupport = itSupportRoleId != null
                && preRoleIds.contains(itSupportRoleId)
                && !targetRoleIds.contains(itSupportRoleId);

        /*
         * 6.交接分支：只有"这次替换摘掉 IT_SUPPORT 且该用户仍负责活动工单"才要求方案与权限。
         *   仅新增角色、或保留 IT_SUPPORT 时，他还能继续处理手上的工单，不需要交接。
         *   交接的工单行锁必须排在用户行锁之前，见 AdminHandoffPort 的契约。
         */
        Map<String, AdminHandoffInstruction> plan = normalizeHandoffs(request.handoffs());
        boolean handoffDone = false;

        if (preRemovesItSupport) {
            List<AdminHandoffAssignmentResult> assignments =
                    adminHandoffPort.findActiveAssignments(userId);
            validateHandoffPlan(assignments, request.handoffReason(), plan);
            if (!plan.isEmpty()) {
                handoffDone = replaceAssignees(userId, request.handoffReason(), plan);
            }
        } else if (!plan.isEmpty()) {
            throw handoffConflict("本次角色替换不需要管理性交接，不接受交接方案");
        }

        //7.再锁用户
        IamUser user = iamUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw userNotFound();
        }

        //8.再锁该用户现有授权关系（按 role_id 升序）
        List<IamUserRole> currentGrants = iamUserRoleMapper.selectByUserIdForUpdate(userId);
        Set<Long> currentRoleIds = currentGrants.stream()
                .map(IamUserRole::getRoleId)
                .collect(Collectors.toSet());

        //9.版本校验
        if (!request.version().equals(user.getVersion())) {
            throw userConflict();
        }

        //10.差集：请求里有库里没有 → 新增；库里有请求里没有 → 删除；两边都有 → 保持原审计字段不动
        List<Long> toAdd = targetRoleIds.stream()
                .filter(roleId -> !currentRoleIds.contains(roleId))
                .toList();

        List<Long> toRemove = currentRoleIds.stream()
                .filter(roleId -> !targetRoleIds.contains(roleId))
                .sorted()
                .toList();

        //11.幂等：集合没有实际变化时直接返回，不改版本、不撤会话
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return getById(userId);
        }

        //12.保护：不允许移除最后一个启用管理员的 SYSTEM_ADMIN 角色
        if (systemAdminRoleId != null
                && toRemove.contains(systemAdminRoleId)
                && user.getStatus() == IamUserStatus.ENABLED
                && countEnabledHolders(systemAdminRoleId) <= 1) {
            throw lastAdminProtected();
        }

        /*
         * 13.写库前的最后一道不变量：这次替换即将摘掉 IT_SUPPORT 时，该用户不得再留有活动工单。
         *   第 6 步依据的是无锁快照，并发授予（RBAC 的授权接口不推进 iam_user.version）可能让它失效，
         *   因此按加锁后的最终差异再确认一次；已经完成交接时用 handoffDone 短路。
         */
        boolean removesItSupport = itSupportRoleId != null
                && toRemove.contains(itSupportRoleId);

        if (removesItSupport && !handoffDone) {
            requireNoActiveAssignmentsLeft(userId, false);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        //14.版本条件更新：id + version 双重条件，并发下只有一个能成功
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

        //15.删除被移除的关系；行数与预期不符说明锁期间的读已不可信
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

        //16.新增缺失的关系，审计两列必须写入
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

        //17.集合实际变化：提交 MySQL 事务前撤销该用户全部旧会话，只执行一次
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

    /**
     * 归一化交接方案：去重后按 {@code ticketNo} 建索引。
     *
     * <p>重复编号是纯请求缺陷（不依赖任何数据库状态），在这里挡成
     * {@code 400/VALIDATION_FAILED}；缺项、多项留给方案校验，因为那要看数据库当前状态。</p>
     */
    private Map<String, AdminHandoffInstruction> normalizeHandoffs(
            List<AdminHandoffInstruction> handoffs) {
        if (handoffs == null || handoffs.isEmpty()) {
            return Map.of();
        }

        Map<String, AdminHandoffInstruction> byTicketNo = new LinkedHashMap<>();
        for (AdminHandoffInstruction instruction : handoffs) {
            if (byTicketNo.putIfAbsent(instruction.ticketNo(), instruction) != null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        HANDOFF_DUPLICATE_TICKET);
            }
        }
        return byTicketNo;
    }

    /**
     * 校验交接方案与目标用户当前活动工单是否匹配，并做条件性权限判断。
     *
     * <p>这一遍用的是无锁快照，目的只是尽早给出"方案不完整"或"缺少交接权限"这类确定性错误；
     * 权威判定在 {@link AdminHandoffPort#replaceAssignees} 内加锁后重做，两者结论不一致时以
     * 锁内结果为准（{@code 409/ADMIN_HANDOFF_CONFLICT}）。</p>
     */
    private void validateHandoffPlan(
            List<AdminHandoffAssignmentResult> assignments,
            String handoffReason,
            Map<String, AdminHandoffInstruction> plan) {

        if (assignments.isEmpty()) {
            if (!plan.isEmpty()) {
                throw handoffConflict("该用户当前没有活动工单，不接受交接方案");
            }
            // 没有活动工单时不要求无依据的原因，也不要求交接权限：8.2 的 USER_MANAGE 契约保持不变
            return;
        }

        if (!currentOperatorPort.hasAuthority(TICKET_ADMIN_HANDOFF)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "目标用户仍负责活动工单，停用或摘除 IT_SUPPORT 需要管理性交接权限");
        }

        if (!StringUtils.hasText(handoffReason)) {
            throw handoffRequired("目标用户仍负责活动工单，交接原因不能为空");
        }

        if (plan.isEmpty()) {
            throw handoffRequired("目标用户仍负责活动工单，必须给出完整交接方案");
        }

        for (AdminHandoffAssignmentResult assignment : assignments) {
            if (!plan.containsKey(assignment.ticketNo())) {
                throw handoffRequired("交接方案缺少工单 " + assignment.ticketNo());
            }
        }

        for (String ticketNo : plan.keySet()) {
            boolean known = assignments.stream()
                    .anyMatch(assignment -> assignment.ticketNo().equals(ticketNo));
            if (!known) {
                throw handoffConflict("工单 " + ticketNo + " 不在该用户当前负责的活动工单内");
            }
        }
    }

    /**
     * 执行交接，并把锁等待超时/死锁映射成可重试的冲突。
     *
     * <p>不自动重放：交接后面紧跟着 Redis 会话撤销，重放会让"撤销"这一副作用重复发生
     * （{@code docs/api-design.md} 8.2.1 的同一条约定）。</p>
     */
    private boolean replaceAssignees(
            long userId,
            String handoffReason,
            Map<String, AdminHandoffInstruction> plan) {
        try {
            adminHandoffPort.replaceAssignees(
                    userId,
                    currentOperatorPort.currentUserId(),
                    handoffReason.strip(),
                    List.copyOf(plan.values()),
                    LocalDateTime.now(clock));
            return true;
        } catch (PessimisticLockingFailureException exception) {
            throw handoffConflict("交接期间工单或账号正在被并发修改，请稍后重试");
        }
    }

    /**
     * 写库前的最后一道不变量：账号/角色变更之后，该用户不得再留有活动工单。
     *
     * <p>前面的交接判定读的是无锁快照，并发转交或授权变更都可能让"当时没有活动工单"失效；
     * 这里按加锁后的最终状态再确认一次。{@code handoffAlreadyDone} 为 {@code true} 表示
     * 本次请求已经在同一事务里交接过，无需重复查询。</p>
     */
    private void requireNoActiveAssignmentsLeft(long userId, boolean handoffAlreadyDone) {
        if (handoffAlreadyDone) {
            return;
        }

        if (!adminHandoffPort.findActiveAssignments(userId).isEmpty()) {
            throw handoffConflict("该用户仍负责活动工单，请重新读取活动工单后补全交接方案");
        }
    }

    private ApiException handoffRequired(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_HANDOFF_REQUIRED",
                message
        );
    }

    private ApiException handoffConflict(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_HANDOFF_CONFLICT",
                message
        );
    }
}
