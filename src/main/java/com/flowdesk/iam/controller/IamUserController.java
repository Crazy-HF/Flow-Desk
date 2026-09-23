package com.flowdesk.iam.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.iam.application.command.*;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.application.service.IamUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fd/v1/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class IamUserController {

    private final IamUserService iamUserService;

    public IamUserController(IamUserService iamUserService) {
        this.iamUserService = iamUserService;
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping
    public R<PageResult<UserResult>> page(@Valid @ModelAttribute UserQuery query) {
        return R.success(iamUserService.page(query));
    }

    /**
     * 根据用户ID查询用户信息
     */
    @GetMapping("/{userId}")
    public R<UserResult> getById(@PathVariable long userId) {
        return R.success(iamUserService.getById(userId));
    }

    /**
     * 创建用户
     */
    @PostMapping
    public ResponseEntity<R<UserResult>> create(@Valid @RequestBody CreateUserCommand request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(iamUserService.create(request)));
    }

    /**
     * 修改用户基本资料。
     */
    @PutMapping("/{userId}")
    public R<UserResult> update(
            @PathVariable long userId,
            @Valid @RequestBody UpdateUserCommand request) {
        return R.success(iamUserService.update(userId, request));
    }

    /**
     * 启用用户
     */
    @PostMapping("/{userId}/actions/enable")
    public R<UserResult> enable(
            @PathVariable long userId,
            @Valid @RequestBody UserStatusChangeCommand request) {
        return R.success(iamUserService.enable(userId, request));
    }

    /**
     * 禁用用户
     */
    @PostMapping("/{userId}/actions/disable")
    public R<UserResult> disable(
            @PathVariable long userId,
            @Valid @RequestBody UserStatusChangeCommand request) {
        return R.success(iamUserService.disable(userId, request));
    }

    /**
     * 替换角色
     */
    @PutMapping("/{userId}/role")
    public R<UserResult> replaceRole(
            @PathVariable long userId,
            @Valid @RequestBody ReplaceUserRolesCommand request) {
        return R.success(iamUserService.replaceRoles(userId, request));
    }

    /**
     * 管理员重置用户密码。
     */
    @PostMapping("/{userId}/actions/reset-password")
    public R<Void> resetPassword(
            @PathVariable long userId,
            @Valid @RequestBody ResetUserPasswordCommand request) {

        iamUserService.resetPassword(userId, request);
        return R.success();
    }
}
