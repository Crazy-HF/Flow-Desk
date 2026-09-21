package com.flowdesk.iam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamRoleCreateVO;
import com.flowdesk.iam.domain.vo.IamRoleQueryVO;
import com.flowdesk.iam.domain.vo.IamRoleUpdateVO;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.service.IamRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Permission;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色管理服务实现（第 3 步动态 RBAC）。
 *
 * <p>职责：角色的分页查询、详情、创建、修改、删除，以及删除时的保护与引用判断。
 * 契约见 {@code docs/api-design.md} 8.2.1，实现细则见 {@code docs/modules/rbac.md} 4.1、第 6 节与第 7 节。</p>
 *
 * <p>并发口径（{@code rbac.md} 第 7 节）：写用例对用于存在性、保护与引用判断的<b>已有记录</b>执行
 * {@code SELECT ... FOR UPDATE}；全局锁顺序为 {@code iam_role → iam_permission → iam_user → 授权关系行}，
 * 同表多行按主键升序，禁止反向获取。只读接口（列表、详情）不加悲观锁。
 * 本类只涉及角色层，权限层与两组授权关系的写入由对应的 Service 承担。</p>
 *
 * <p>授权关系不在这里维护：创建角色不会自动授予任何权限；删除角色也不级联清理授权行，
 * 仍被引用时直接拒绝（第 6 节第 3 条）。</p>
 */
@Slf4j
@Service
public class IamRoleServiceImpl implements IamRoleService {
    /** 排序白名单：取值为数据库列名，与 {@code rbac.md} 4.1 的排序约定一致；白名单之外一律 400。 */
    private static final Set<String> ROLE_ORDER_FIELDS =
            Set.of("code", "name", "created_at");

    /** 受保护的内置管理员角色编码：按 {@code code} 常量判定，不新增“内置”标记列（阶段设计决策 5）。 */
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final Clock clock;
    private final IamRoleMapper iamRoleMapper;
    private final IamUserRoleMapper iamUserRoleMapper;
    private final IamRolePermissionMapper iamRolePermissionMapper;

    public IamRoleServiceImpl(Clock clock,IamRoleMapper iamRoleMapper,IamUserRoleMapper iamUserRoleMapper, IamRolePermissionMapper iamRolePermissionMapper) {
        this.clock = clock;
        this.iamRoleMapper = iamRoleMapper;
        this.iamUserRoleMapper = iamUserRoleMapper;
        this.iamRolePermissionMapper = iamRolePermissionMapper;
    }

    /**
     * 分页查询角色。
     *
     * <p>流程：拼分页参数与排序 → 按 {@code keyword} 组条件 → 查主表 → 空结果直接返回 →
     * 批量查授权关系并分组 → 转换。只读，不加锁。</p>
     *
     * @param query 分页与筛选参数
     * @return 角色分页结果，每项带已授权的 {@code permissionIds}
     */
    @Override
    public PageResult<IamRoleBO> page(IamRoleQueryVO query) {
        //创建分页查询参数：pageNo >= 1、pageSize ∈ [1,100] 由 PageQuery 上的 @NotNull/@Min/@Max 保证
        Page<IamRole> rolePage = new Page<>(
                query.getCurrent(),
                query.getSize()
        );
        //创建排序字段：白名单外的字段或非 asc/desc 的方向会在这里抛 400/VALIDATION_FAILED
        List<OrderItem> orderItems = query.orderItems(ROLE_ORDER_FIELDS);
        //未请求排序时补默认排序 id asc：分页必须有稳定顺序，否则并发写入下翻页会重复或漏项
        if (orderItems.isEmpty()) {
            rolePage.addOrder(OrderItem.asc("id"));
        } else {
            rolePage.addOrder(orderItems);
            rolePage.addOrder(OrderItem.asc("id"));
        }

        //模糊查询：keyword 同时匹配 code 与 name，用 and(...) 把 OR 包起来，
        // 否则 OR 会与分页/后续条件平级，把查询范围放大
        LambdaQueryWrapper<IamRole> roleQuery =
                new LambdaQueryWrapper<>();

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            roleQuery.and(wrapper -> wrapper
                    .like(IamRole::getCode, keyword)
                    .or()
                    .like(IamRole::getName, keyword));
        }
        //查询结果：只查当前页，总数由 MyBatis-Plus 分页插件在同一次调用里带出
        Page<IamRole> result =
                iamRoleMapper.selectPage(rolePage, roleQuery);

        //查询结果处理：本页没有角色时不再去查授权关系，直接返回空列表（少一次 SQL）
        if(result.getRecords().isEmpty()){
            return PageResult.from(result,iamRole -> toBO(iamRole, List.of()));
        }

        //获取用户角色Id列表：用本页的角色 ID 集合，为下一步的批量查询做准备
        List<Long> roleIds = result.getRecords().stream()
                .map(IamRole::getId)
                .toList();

        //根据角色Id获取角色权限ID列表：一次 IN 查询取回本页所有角色的授权关系，避免按行循环查库（N+1），
        // 并在 SQL 层按 (role_id, permission_id) 升序，使每个角色的 permissionIds 顺序稳定
        Map<Long, List<Long>> permissionIdsByRole = iamRolePermissionMapper.selectList(
                new LambdaQueryWrapper<IamRolePermission>()
                        .in(IamRolePermission::getRoleId, roleIds)
                        .orderByAsc(IamRolePermission::getRoleId,IamRolePermission::getPermissionId))
                .stream()
                .collect(Collectors.groupingBy(
                        IamRolePermission::getRoleId,
                        Collectors.mapping(IamRolePermission::getPermissionId, Collectors.toList())));

        //转换结果：按角色 ID 取回该角色的授权列表
        return PageResult.from(
                result,
                role -> toBO(role, permissionIdsByRole.getOrDefault(role.getId(), List.of())));
    }

    /**
     * 查询角色详情。
     *
     * <p>只读，不加锁。非正整数按“资源不存在”处理，返回 {@code 404} 而不是 {@code 400}
     * （与用户详情接口的既有做法一致，见 {@code rbac.md} 第 5 节）。</p>
     *
     * @param roleId 角色 ID
     * @return 角色详情（含已授权的 {@code permissionIds}）
     */
    @Override
    public IamRoleBO getById(long roleId) {
        // 路径变量非正整数直接当“不存在”，避免 400/404 两套语义
        if (roleId <= 0) {
            throw roleNotFound();
        }

        IamRole role = iamRoleMapper.selectById(roleId);
        if (role == null) {
            throw roleNotFound();
        }

        //根据角色Id获取角色权限ID列表

        return toBO(role, findPermissionIds(roleId));
    }

    /**
     * 创建角色。
     *
     * <p>流程：先查编码是否已存在（给出确定的 {@code 409}）→ 落库 → 返回新角色。
     * 先查一次不等于安全：并发下仍可能同时通过检查，因此数据库唯一索引是最终防线，
     * 由 {@link DuplicateKeyException} 兜底转成同一个错误码。</p>
     *
     * @param request 角色编码、名称与可选描述
     * @return 创建后的角色（{@code permissionIds} 为空，新角色还没有授权关系）
     */
    @Override
    @Transactional
    public IamRoleBO create(IamRoleCreateVO request) {

        //1.查询当前编码是否存在
        Long existingCount = iamRoleMapper.selectCount(
                new LambdaQueryWrapper<IamRole>()
                        .eq(IamRole::getCode, request.code())
        );

        if (existingCount > 0) {
            throw roleCodeConflict();
        }

        //创建Role信息：只写调用方提交的字段，createdAt 由注入时钟决定（不信任客户端时间）
        IamRole role = new IamRole();
        role.setCode(request.code());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setCreatedAt(LocalDateTime.now(clock));

        try {
            iamRoleMapper.insert(role);
        } catch (DuplicateKeyException exception) {
            // 并发插入同一编码：唯一索引 uk_iam_role_code 报错，语义与上面的预检查一致
            throw roleCodeConflict();
        }

        // 新角色没有任何权限，显式返回空集合，避免调用方把 null 当成“未知”
        return toBO(role, List.of());
    }

    /**
     * 修改角色的名称与描述。
     *
     * <p>流程：先 {@code SELECT ... FOR UPDATE} 锁住目标角色（锁顺序的第一层），
     * 锁内做存在性判断并重新读取当前值，再按条件更新，最后回填修改后的字段返回。
     * 请求体没有 {@code code}，因此本用例不会修改编码。</p>
     */
    @Override
    @Transactional
    public IamRoleBO update(long roleId, IamRoleUpdateVO request) {
        if (roleId <= 0) {
            throw roleNotFound();
        }

        //锁定角色：与后续可能的授权关系写操作保持“角色优先”的统一锁顺序，避免交叉等待
        IamRole role = iamRoleMapper.selectByIdForUpdate(roleId);
        if (role == null) {
            throw roleNotFound();
        }

        //更新角色信息：按 id 条件更新，name/description 直接取请求值（接口语义是整体覆盖这两列）
        iamRoleMapper.update(
                null,
                Wrappers.<IamRole>lambdaUpdate()
                        .eq(IamRole::getId, roleId)
                        .set(IamRole::getName, request.name())
                        .set(IamRole::getDescription, request.description())
        );

        // 回填内存对象，让响应与库内结果一致（不必为读回刚写的值再查一次库）
        role.setName(request.name());
        role.setDescription(request.description());

        return toBO(role, findPermissionIds(roleId));
    }

    /**
     * 删除角色。
     *
     * <p>流程：锁住目标角色 → 保护判断（{@code SYSTEM_ADMIN} 禁止删除）→ 引用判断
     * （仍被 {@code iam_user_role} 或 {@code iam_role_permission} 引用则拒绝）→ 删除。
     * 引用判断与删除之间的竞态由外键兜底：{@link DataIntegrityViolationException} 转成同一个
     * {@code 409/RBAC_CONFLICT}。</p>
     */
    @Override
    @Transactional
    public void delete(long roleId) {
        if (roleId <= 0) {
            throw roleNotFound();
        }

        IamRole role = iamRoleMapper.selectByIdForUpdate(roleId);
        if (role == null) {
            throw roleNotFound();
        }

        //系统管理员不可删除：受保护对象按 code 常量判定（第 6 节第 1 条）
        if (SYSTEM_ADMIN.equals(role.getCode())) {
            throw rbacConflict("系统管理员角色不能删除");
        }

        //检查角色是否被用户使用：i.e. iam_user_role 中是否还有指向该角色的行
        Long userRoleCount = iamUserRoleMapper.selectCount(
                new LambdaQueryWrapper<IamUserRole>()
                        .eq(IamUserRole::getRoleId, roleId)
        );

        //检查角色是否被权限使用：i.e. iam_role_permission 中是否还有该角色的授权行
        Long rolePermissionCount = iamRolePermissionMapper.selectCount(
                new LambdaQueryWrapper<IamRolePermission>()
                        .eq(IamRolePermission::getRoleId, roleId)
        );

        // 两类引用都禁止删除，且不做级联清理（第 6 节第 3 条）
        if (userRoleCount > 0 || rolePermissionCount > 0) {
            throw rbacConflict("角色仍被授权关系引用，不能删除");
        }

        // 并发兜底：上面的计数与这次删除之间若有其他事务插入了授权行，外键会阻止删除
        try {
            iamRoleMapper.deleteById(roleId);
        } catch (DataIntegrityViolationException exception) {
            throw rbacConflict("角色仍被授权关系引用，不能删除");
        }

    }

    /**
     * 查询某角色已授权的权限 ID 列表。
     *
     * <p>只读且不加锁；按 {@code permission_id} 升序，保证同一个角色每次返回的顺序一致。</p>
     *
     * @param roleId 角色 ID
     * @return 权限 ID 列表，无授权时为空列表
     */
    private List<Long> findPermissionIds(long roleId) {
        return iamRolePermissionMapper.selectList(
                        new LambdaQueryWrapper<IamRolePermission>()
                                .eq(IamRolePermission::getRoleId, roleId)
                                .orderByAsc(IamRolePermission::getPermissionId))
                .stream()
                .map(IamRolePermission::getPermissionId)
                .toList();
    }

    /**
     * 把持久层实体转换成对外 BO。
     *
     * <p>{@code iam_role.created_at} 按 UTC 写入，这里补上 {@code UTC} 偏移，
     * 使接口按全局契约返回带偏移的 ISO 8601 时间。</p>
     */
    private IamRoleBO toBO(IamRole role, List<Long> permissionIds) {
        return new IamRoleBO(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.getCreatedAt().atOffset(ZoneOffset.UTC),
                permissionIds
        );
    }

    /** 角色不存在：非正整数 ID 与查询为空都走这里，统一 {@code 404/ROLE_NOT_FOUND}。 */
    private ApiException roleNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND",
                "角色不存在"
        );
    }

    /** 角色编码重复：预检查命中或唯一索引冲突（并发）都走这里，{@code 409/ROLE_CODE_CONFLICT}。 */
    private ApiException roleCodeConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "ROLE_CODE_CONFLICT",
                "角色编码已存在"
        );
    }

    /**
     * 违反 RBAC 保护或引用约束，{@code 409/RBAC_CONFLICT}。
     */
    private ApiException rbacConflict(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT",
                message
        );
    }
}
