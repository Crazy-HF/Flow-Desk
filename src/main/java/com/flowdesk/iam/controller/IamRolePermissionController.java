package com.flowdesk.iam.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;
import com.flowdesk.iam.application.service.IamRolePermissionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/fd/v1/admin/role-permissions")
@PreAuthorize("hasAuthority('RBAC_MANAGE')")
public class IamRolePermissionController {

    private final IamRolePermissionService iamRolePermissionService;

    public IamRolePermissionController(
            IamRolePermissionService iamRolePermissionService
    ) {
        this.iamRolePermissionService = iamRolePermissionService;
    }

    /**
     * 按角色或权限分页查询授权关系。
     * @param query
     * @return
     */
    @GetMapping
    public R<PageResult<RolePermissionResult>> page(
            @Valid @ModelAttribute RolePermissionQuery query
    ) {
        return R.success(iamRolePermissionService.page(query));
    }

    /**
     * 授予角色权限。
     * 重复授权保持幂等，返回原授权记录。
     * @param request
     * @return
     */
    @PostMapping
    public R<List<RolePermissionResult>> grant(
            @Valid @RequestBody GrantRolePermissionsCommand request
    ) {
        return R.success(iamRolePermissionService.grant(request));
    }

    /**
     * 撤销角色权限。
     * @param roleId
     * @param permissionId
     * @return
     */
    @DeleteMapping("/{roleId}/{permissionId}")
    public R<Void> revoke(
            @PathVariable long roleId,
            @PathVariable long permissionId
    ) {
        iamRolePermissionService.revoke(roleId, permissionId);
        return R.success();
    }

    @PostMapping("/actions/revoke")
    public R<Void> revokeBatch(
            @Valid @RequestBody RevokeRolePermissionsCommand request
    ) {
        iamRolePermissionService.revokeBatch(request);
        return R.success();
    }

    @DeleteMapping("/roles/{roleId}")
    public R<Void> clearAll(@PathVariable long roleId) {
        iamRolePermissionService.clearAll(roleId);
        return R.success();
    }
}
