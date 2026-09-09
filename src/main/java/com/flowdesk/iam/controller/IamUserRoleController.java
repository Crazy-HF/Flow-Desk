package com.flowdesk.iam.controller;

import com.flowdesk.iam.domain.bo.IamUserRoleBO;
import com.flowdesk.iam.domain.vo.GrantURVO;
import com.flowdesk.iam.domain.vo.IamUserRoleVO;
import com.flowdesk.iam.service.IamUserRoleService;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import com.flowdesk.shared.web.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户角色授权管理入口。
 *
 * <p>计划端点：</p>
 * <ul>
 *     <li>{@code GET /fd/v1/admin/user-roles}：按用户或角色分页查询授权关系</li>
 *     <li>{@code POST /fd/v1/admin/user-roles}：为用户授予角色</li>
 *     <li>{@code DELETE /fd/v1/admin/user-roles/}：撤销用户角色</li>
 * </ul>
 *
 * <p>授权关系本身没有可修改业务字段；变更角色应撤销旧授权后重新授予，故不设置 {@code PUT}。</p>
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/fd/v1/admin/user-roles")
public class IamUserRoleController {
    private final IamUserRoleService iamUserRoleService;
    public IamUserRoleController(IamUserRoleService iamUserRoleService) {
        this.iamUserRoleService = iamUserRoleService;
    }

    /**
     * 按用户或角色分页查询授权关系。
     */
    @GetMapping("")
    public R<PageResult<IamUserRoleBO>> getUserRolePage(IamUserRoleVO userRoleVO, PageQuery pageQuery) {
        return R.success(iamUserRoleService.getUserRolePage(userRoleVO, pageQuery));
    }

    /**
     * 为用户授予角色。
     */
    @PostMapping("")
    public R<String> grantRole(GrantURVO grantURVO) {
        iamUserRoleService.grantRole(grantURVO);
        return R.success("授权成功");
    }

    /**
     * 撤销用户角色
     */
    @DeleteMapping("")
    public R<Boolean> revokeRole(@PathVariable Long userId, @PathVariable List<Long> roleIds) {
        return R.success(iamUserRoleService.revokeRoles(userId, roleIds));
    }

    @DeleteMapping("/{userId}/{roleId}")
    public R<?> revokeRole(@PathVariable Long userId, @PathVariable Long roleId) {
        return R.success(iamUserRoleService.revokeRole(userId, roleId));
    }
}
