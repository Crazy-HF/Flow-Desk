package com.flowdesk.iam.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;
import com.flowdesk.iam.application.service.IamUserRoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/fd/v1/admin/user-roles")
@PreAuthorize("hasAuthority('RBAC_MANAGE')")
public class IamUserRoleController {

    private final IamUserRoleService iamUserRoleService;

    public IamUserRoleController(IamUserRoleService iamUserRoleService) {
        this.iamUserRoleService = iamUserRoleService;
    }

    @GetMapping
    public R<PageResult<UserRoleResult>> page(
            @Valid @ModelAttribute UserRoleQuery query) {
        return R.success(iamUserRoleService.page(query));
    }

    @PostMapping
    public R<List<UserRoleResult>> grant(
            @Valid @RequestBody GrantUserRolesCommand request) {
        return R.success(iamUserRoleService.grant(request));
    }

    @DeleteMapping("/{userId}/{roleId}")
    public R<Void> revoke(
            @PathVariable long userId,
            @PathVariable long roleId) {
        iamUserRoleService.revoke(userId, roleId);
        return R.success();
    }

    @PostMapping("/actions/revoke")
    public R<Void> revokeBatch(
            @Valid @RequestBody RevokeUserRolesCommand request) {
        iamUserRoleService.revokeBatch(request);
        return R.success();
    }

    @DeleteMapping("/users/{userId}")
    public R<Void> clearAll(@PathVariable long userId) {
        iamUserRoleService.clearAll(userId);
        return R.success();
    }

    @PostMapping("/actions/grant-users")
    public R<List<UserRoleResult>> grantUsers(
            @Valid @RequestBody GrantRoleToUsersCommand request) {
        return R.success(iamUserRoleService.grantUsers(request));
    }
}
