package com.flowdesk.iam.controller;

import com.flowdesk.iam.domain.bo.IamUserBO;
import com.flowdesk.iam.domain.vo.IamUserCreateVO;
import com.flowdesk.iam.domain.vo.IamUserResetPasswordVO;
import com.flowdesk.iam.domain.vo.IamUserRoleUpdateVO;
import com.flowdesk.iam.domain.vo.IamUserVO;
import com.flowdesk.iam.service.IamUserService;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import com.flowdesk.shared.web.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 系统管理员的用户与凭据管理入口。
 *
 * <p>Controller 不设置类级权限。管理他人账号的方法在创建时单独使用
 * {@code @PreAuthorize("hasAuthority('USER_MANAGE')")}；用户本人可访问的方法按其专属权限规则定义。
 * 当前仅建立 API 边界，不提前实现 M5 的用户管理用例。</p>
 *
 * <p>M5（TASK-051）在此控制器补充以下端点：</p>
 * <ul>
 *     <li>{@code GET /fd/v1/users}：分页查询用户</li>
 *     <li>{@code GET /fd/v1/users/{userId}}：查询用户详情</li>
 *     <li>{@code POST /fd/v1/users}：创建用户</li>
 *     <li>{@code PUT /fd/v1/users/{userId}}：修改基本资料</li>
 *     <li>{@code POST /fd/v1/users/{userId}/actions/enable}：启用账号</li>
 *     <li>{@code POST /fd/v1/users/{userId}/actions/disable}：停用账号及必要交接</li>
 *     <li>{@code PUT /fd/v1/users/{userId}/roles}：替换完整角色集合</li>
 *     <li>{@code POST /fd/v1/users/{userId}/actions/reset-password}：重置密码并撤销会话</li>
 * </ul>
 */
@Tag(name = "用户与凭据管理")
@RestController
@RequestMapping("/fd/v1/users")
public class IamUserController {


    private final IamUserService iamUserService;

    public IamUserController(IamUserService iamUserService) {
        this.iamUserService = iamUserService;
    }

    /**
     * 分页查询用户
     */
    @GetMapping("")
    public R<PageResult<IamUserBO>> listIamUsers(IamUserVO iamUserVO, @Valid PageQuery pageQuery) {
        return R.success(iamUserService.listIamUsers(iamUserVO, pageQuery));
    }

    /**
     * 查询用户详情
     */
    @GetMapping("/{userId}")
    public R<IamUserBO> getIamUserById(@PathVariable Long userId) {
        return R.success(iamUserService.getIamUserById(userId));
    }

    /**
     * 创建用户
     */
    @PostMapping("")
    public R<IamUserBO> createIamUser(@Valid @RequestBody IamUserCreateVO iamUserVO) {
        return R.success(iamUserService.createIamUser(iamUserVO));
    }

    /**
     * 修改用户
     */
    @PutMapping("/{userId}")
    public R<IamUserBO> updateIamUser(@PathVariable Long userId,  @Valid @RequestBody IamUserVO iamUserVO) {
        return R.success(iamUserService.updateIamUser(userId, iamUserVO));
    }

    /**
     * 启用用户账号。用户的历史身份和业务记录仍然保留。
     */
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @PostMapping("/{userId}/actions/enable")
    public R<Void> enableIamUser(@PathVariable Long userId, @RequestParam("expectedVersion") Long expectedVersion) {
        iamUserService.enableIamUser(userId, expectedVersion);
        return R.success();
    }
    /**
     * 停用用户账号。用户的历史身份和业务记录仍然保留。
     */
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @PostMapping("/{userId}/actions/disable")
    public R<Void> disableIamUser(@PathVariable Long userId, @RequestParam("expectedVersion") Long expectedVersion) {
        iamUserService.disableIamUser(userId,expectedVersion);
        return R.success();
    }


    /**
     * 替换用户角色
     */
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @PutMapping("/{userId}/roles")
    public R<Void> updateIamUserRoles(@PathVariable Long userId, @Valid @RequestBody IamUserRoleUpdateVO roleUpdateVO) {
        // TODO: 实现替换用户角色逻辑
        return R.success();
    }

    /**
     * 重置用户密码并撤销会话
     */
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @PostMapping("/{userId}/actions/reset-password")
    public R<Void> resetIamUserPassword(
            @PathVariable Long userId,
            @Valid @RequestBody IamUserResetPasswordVO request
    ) {
        iamUserService.resetIamUserPassword(userId, request);
        return R.success();
    }
}
