package com.flowdesk.iam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamCrateRoleVO;
import com.flowdesk.iam.domain.vo.IamRoleVO;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.service.IamRoleService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flowdesk.shared.exception.ApiException;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 预置角色 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Slf4j
@Service
public class IamRoleServiceImpl extends ServiceImpl<IamRoleMapper, IamRole> implements IamRoleService {

    private final IamRoleMapper iamRoleMapper;

    public IamRoleServiceImpl(IamRoleMapper iamRoleMapper) {
        this.iamRoleMapper = iamRoleMapper;
    }

    /**
     * 根据条件查询角色列表
     */
    @Override
    public PageResult<IamRoleBO> getRoleList(IamRoleVO iamRoleVO, PageQuery pageQuery) {
        //1. 参数防空
        iamRoleVO = Objects.requireNonNullElse(iamRoleVO, new IamRoleVO());
        pageQuery = Objects.requireNonNullElse(pageQuery, new PageQuery());

        //2. 构建查询条件
        LambdaQueryWrapper<IamRole> queryWrapper = new LambdaQueryWrapper<IamRole>()
                .select(IamRole::getId, IamRole::getCode, IamRole::getName, IamRole::getDescription)
                .like(IamRole::getName, iamRoleVO.getName())
                .eq(IamRole::getCode, iamRoleVO.getCode());

        //3.构建分页查询
        Page<IamRole> page = new Page<>(pageQuery.getPageNo(), pageQuery.getPageSize());

        //4.动态排序
        List<OrderItem> orderItems = pageQuery.getOrderItems();
        if (!orderItems.isEmpty()) {
            page.addOrder(orderItems);
        }else {
            // 默认排序
            page.addOrder(OrderItem.desc("created_at"), OrderItem.desc("id"));
        }

        //5.执行查询
        Page<IamRole> rolePage = page(page, queryWrapper);

        // 5. 转换为稳定的接口分页结构，
        return PageResult.from(rolePage, this::toRoleBO);
    }

    /**
     * 根据角色ID获取角色信息
     */
    @Override
    public IamRoleBO getIamRoleById(Long roleId) {
        validateRoleId(roleId);

        IamRole role = iamRoleMapper.selectById(roleId);

        if(role == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "ROLE_NOT_FOUND",
                    "角色不存在");
        }
        return toRoleBO(role);
    }

    /**
     * 根据角色ID列表获取角色信息
     */
    @Override
    public List<IamRoleBO> getIamRoleByIds(List<Long> roleIds) {
        if(CollectionUtils.isEmpty(roleIds))
            throw new IllegalArgumentException("角色ID列表不能为空");

        //2.去重
        Set<Long> distinctRoles = roleIds.stream()
                .distinct()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (distinctRoles.isEmpty())
            throw new IllegalArgumentException("角色列表不能为空");

        //3.执行查询
        List<IamRole> roles = iamRoleMapper.selectBatchIds(distinctRoles);

        //
        Set<Long> foundIds = roles.stream()
                .map(IamRole::getId)
                .collect(Collectors.toSet());


        return roles.stream().map(this::toRoleBO).toList();
    }


    /**
     * 创建角色
     */
    @Override
    public IamRoleBO createRole(IamCrateRoleVO iamRoleVO) {
        Objects.requireNonNull(iamRoleVO, "角色信息不能为空");

        //校验角色编码是否存在
        LambdaQueryWrapper<IamRole> queryWrapper = new LambdaQueryWrapper<IamRole>()
                .eq(IamRole::getCode, iamRoleVO.getCode());
        IamRole role = iamRoleMapper.selectOne(queryWrapper);
        if (role != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ROLE_CODE_ALREADY_EXISTS",
                    "角色编码已存在");
        }

        // 创建新角色
        IamRole newRole = new IamRole();
        newRole.setCode(iamRoleVO.getCode());
        newRole.setName(iamRoleVO.getName());
        newRole.setDescription(iamRoleVO.getDescription());

        // 保存新角色
        try {
            if (baseMapper.insert(newRole) != 1)
                throw new IllegalStateException("创建角色失败");
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_CODE_CONFLICT", "角色编码已存在");
        }

        return toRoleBO(newRole);
    }

    /**
     * 更新角色信息
     */
    @Override
    public IamRoleBO updateRole(Long roleId, IamRoleVO iamRoleVO) {
        validateRoleId(roleId);
        Objects.requireNonNull(iamRoleVO, "角色信息不能为空");

        IamRole role = iamRoleMapper.selectById(roleId);
        if (role == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "ROLE_NOT_FOUND",
                    "角色不存在");
        }

        role.setName(iamRoleVO.getName());
        role.setDescription(iamRoleVO.getDescription());
        if (baseMapper.updateById(role) != 1)
            throw new IllegalStateException("更新角色失败");

        return toRoleBO(role);
    }

    /**
     * 删除角色
     */
    @Override
    public Void deleteRole(Long roleId) {
        validateRoleId(roleId);
        int rows = baseMapper.deleteById(roleId);

        if (rows != 1)
            throw new IllegalStateException("删除角色失败");
        return null;
    }


    private IamRoleBO toRoleBO(IamRole iamRole) {
        IamRoleBO iamRoleBO = new IamRoleBO();
        iamRoleBO.setId(iamRole.getId());
        iamRoleBO.setCode(iamRole.getCode());
        iamRoleBO.setName(iamRole.getName());
        iamRoleBO.setDescription(iamRole.getDescription());
        return iamRoleBO;
    }

    private void validateRoleId(Long roleId) {
        if (roleId == null || roleId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE_ID",
                    "角色 ID 必须为正整数");
        }
    }
}
