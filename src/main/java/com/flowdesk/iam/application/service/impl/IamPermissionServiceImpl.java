package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.application.result.PermissionResult;
import com.flowdesk.iam.application.command.CreatePermissionCommand;
import com.flowdesk.iam.application.query.PermissionQuery;
import com.flowdesk.iam.application.command.UpdatePermissionCommand;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.application.service.IamPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限管理服务实现。
 *
 * <p>负责权限分页、详情、创建、更新和删除，并在写操作中维护编码唯一性、
 * 内置权限保护及角色引用约束。更新和删除会在事务内锁定目标权限，避免并发
 * 操作破坏检查与写入之间的一致性。</p>
 */
@Slf4j
@Service
public class IamPermissionServiceImpl implements IamPermissionService {
    /** 不允许删除的 RBAC 管理权限编码。 */
    private static final String RBAC_MANAGE = "RBAC_MANAGE";

    /** 客户端允许指定的权限排序字段，其他字段由查询对象过滤。 */
    private static final Set<String> PERMISSION_ORDER_FIELDS =
            Set.of("code", "name", "created_at");

    private final Clock clock;
    private final IamPermissionMapper iamPermissionMapper;
    private final IamRolePermissionMapper iamRolePermissionMapper;

    /**
     * 创建权限管理服务。
     *
     * @param clock 生成 UTC 创建时间所使用的时钟
     * @param iamPermissionMapper 权限数据访问组件
     * @param iamRolePermissionMapper 角色权限关系数据访问组件
     */
    public IamPermissionServiceImpl(Clock clock,
            IamPermissionMapper iamPermissionMapper,
            IamRolePermissionMapper iamRolePermissionMapper
    ) {
        this.clock = clock;
        this.iamPermissionMapper = iamPermissionMapper;
        this.iamRolePermissionMapper = iamRolePermissionMapper;
    }

    /**
     * 分页查询权限，并批量加载当前页权限关联的角色 ID，避免逐条查询关系数据。
     * 排序字段来自白名单，且始终追加 ID 升序作为稳定排序条件。
     *
     * @param query 分页、关键字和排序条件
     * @return 权限分页结果
     */
    @Override
    public PageResult<PermissionResult> page(PermissionQuery query) {
        //1.创建分页对象
        Page<IamPermission> permissionPage =
                new Page<>(query.getCurrent(), query.getSize());

        //2.排序白名单
        List<OrderItem> orderItems =
                query.orderItems(PERMISSION_ORDER_FIELDS);

        if (orderItems.isEmpty()) {
            permissionPage.addOrder(OrderItem.asc("id"));
        } else {
            permissionPage.addOrder(orderItems);
            permissionPage.addOrder(OrderItem.asc("id"));
        }

        //3.keyword 模糊匹配
        LambdaQueryWrapper<IamPermission> permissionQuery =
                new LambdaQueryWrapper<>();

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            permissionQuery.and(wrapper -> wrapper
                    .like(IamPermission::getCode, keyword)
                    .or()
                    .like(IamPermission::getName, keyword));
        }

        //4.执行分页查询
        Page<IamPermission> result =
                iamPermissionMapper.selectPage(permissionPage, permissionQuery);

        //5.空页处理
        if (result.getRecords().isEmpty()) {
            return PageResult.from(
                    result,
                    permission -> toResult(permission, List.of())
            );
        }

        //6.收集当前页权限 ID，一次查询角色权限关系
        List<Long> permissionIds = result.getRecords().stream()
                .map(IamPermission::getId)
                .toList();

        Map<Long, List<Long>> roleIdsByPermission =
                iamRolePermissionMapper.selectList(
                        new LambdaQueryWrapper<IamRolePermission>()
                                .in(IamRolePermission::getPermissionId, permissionIds)
                                .orderByAsc(
                                        IamRolePermission::getPermissionId,
                                        IamRolePermission::getRoleId
                                )
                ).stream().collect(Collectors.groupingBy(
                        IamRolePermission::getPermissionId,
                        Collectors.mapping(
                                IamRolePermission::getRoleId,
                                Collectors.toList()
                        )
                ));

        //7.转换分页结果
        return PageResult.from(result,
                permission -> toResult(permission,
                        roleIdsByPermission.getOrDefault(permission.getId(), List.of())));
    }

    /**
     * 查询权限详情及其关联的角色 ID。
     *
     * @param permissionId 权限 ID
     * @return 权限详情
     */
    @Override
    public PermissionResult getById(long permissionId) {
        if (permissionId <= 0) {
            throw permissionNotFound();
        }

        IamPermission permission =
                iamPermissionMapper.selectById(permissionId);

        if (permission == null) {
            throw permissionNotFound();
        }

        return toResult(permission, findRoleIds(permissionId));
    }

    /**
     * 创建权限。先通过普通查询提供明确的重复编码提示，再依靠数据库唯一键
     * 处理并发创建竞争；唯一键异常仅转换为权限编码冲突。
     *
     * @param request 权限创建参数
     * @return 已创建的权限
     */
    @Override
    @Transactional
    public PermissionResult create(CreatePermissionCommand request) {
        //1.校验权限编码是否已存在
        Long existingCount = iamPermissionMapper.selectCount(
                new LambdaQueryWrapper<IamPermission>()
                        .eq(IamPermission::getCode, request.code())
        );

        if (existingCount > 0) {
            throw permissionCodeConflict();
        }

        //2.创建权限
        IamPermission permission = new IamPermission();
        permission.setCode(request.code());
        permission.setName(request.name());
        permission.setDescription(request.description());
        permission.setCreatedAt(LocalDateTime.now(clock));

        //3.插入权限
        try {
            iamPermissionMapper.insert(permission);
        } catch (DuplicateKeyException exception) {
            throw permissionCodeConflict();
        }

        return toResult(permission, List.of());
    }

    /**
     * 在事务内锁定目标权限后更新名称和描述，权限编码不会参与更新。
     *
     * @param permissionId 权限 ID
     * @param request 权限更新参数
     * @return 更新后的权限详情
     */
    @Override
    @Transactional
    public PermissionResult update(long permissionId, UpdatePermissionCommand request) {

        if (permissionId <= 0) {
            throw permissionNotFound();
        }
        //1.查询并添加行锁，防止并发修改
        IamPermission permission =
                iamPermissionMapper.selectByIdForUpdate(permissionId);

        if (permission == null) {
            throw permissionNotFound();
        }

        //2.更新权限名称和描述
        iamPermissionMapper.update(
                null,
                Wrappers.<IamPermission>lambdaUpdate()
                        .eq(IamPermission::getId, permissionId)
                        .set(IamPermission::getName, request.name())
                        .set(
                                IamPermission::getDescription,
                                request.description()
                )
        );

        //3.同步内存对象，供响应转换使用
        permission.setName(request.name());
        permission.setDescription(request.description());

        return toResult(permission, findRoleIds(permissionId));
    }

    /**
     * 在事务内锁定并删除权限。RBAC 管理权限和仍被角色引用的权限不可删除，
     * 数据库完整性约束作为并发场景下的最后一道保护。
     *
     * @param permissionId 权限 ID
     */
    @Override
    @Transactional
    public void delete(long permissionId) {
        if (permissionId <= 0) {
            throw permissionNotFound();
        }

        //1.查询并添加行锁，防止并发修改
        IamPermission permission =
                iamPermissionMapper.selectByIdForUpdate(permissionId);

        if (permission == null) {
            throw permissionNotFound();
        }

        //2.检查权限码是否为 RBAC_MANAGE
        if (RBAC_MANAGE.equals(permission.getCode())) {
            throw rbacConflict("RBAC_MANAGE 权限不能删除");
        }

        //3.检查权限是否被角色引用
        Long referenceCount = iamRolePermissionMapper.selectCount(
                new LambdaQueryWrapper<IamRolePermission>()
                        .eq(
                                IamRolePermission::getPermissionId,
                                permissionId
                        )
        );

        if (referenceCount > 0) {
            throw rbacConflict("权限仍被角色引用，不能删除");
        }

        //4.删除权限
        try {
            iamPermissionMapper.deleteById(permissionId);
        } catch (DataIntegrityViolationException exception) {
            throw rbacConflict("权限仍被角色引用，不能删除");
        }
    }

    /**
     * 将权限实体转换为对外业务对象。
     *
     * @param permission 权限实体
     * @param roleIds 引用该权限的角色 ID
     * @return 权限业务对象
     */
    private PermissionResult toResult(IamPermission permission, List<Long> roleIds) {
        return new PermissionResult(
                permission.getId(),
                permission.getCode(),
                permission.getName(),
                permission.getDescription(),
                permission.getCreatedAt().atOffset(ZoneOffset.UTC),
                roleIds
        );
    }

    /**
     * 按角色 ID 升序查询引用指定权限的角色。
     *
     * @param permissionId 权限 ID
     * @return 角色 ID 列表
     */
    private List<Long> findRoleIds(long permissionId) {
        return iamRolePermissionMapper.selectList(
                        new LambdaQueryWrapper<IamRolePermission>()
                                .eq(
                                        IamRolePermission::getPermissionId,
                                        permissionId
                                )
                                .orderByAsc(IamRolePermission::getRoleId)
                ).stream()
                .map(IamRolePermission::getRoleId)
                .toList();
    }

    /**
     * 创建权限不存在异常。
     *
     * @return 404/PERMISSION_NOT_FOUND 异常
     */
    private ApiException permissionNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "PERMISSION_NOT_FOUND",
                "权限不存在"
        );
    }

    /**
     * 创建权限编码冲突异常。
     *
     * @return 409/PERMISSION_CODE_CONFLICT 异常
     */
    private ApiException permissionCodeConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "PERMISSION_CODE_CONFLICT",
                "权限编码已存在"
        );
    }

    /**
     * 创建 RBAC 关系冲突异常。
     *
     * @param message 面向调用方的冲突说明
     * @return 409/RBAC_CONFLICT 异常
     */
    private ApiException rbacConflict(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT",
                message
        );
    }
}
