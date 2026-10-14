package com.flowdesk.iam.controller;

import com.flowdesk.common.web.R;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.application.service.IamUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理性交接的读取侧：目标用户当前负责的待交接工单（{@code docs/api-design.md} 8.3）。
 *
 * <p>路径在 {@code /fd/v1/admin} 下、权限是 {@code TICKET_ADMIN_HANDOFF}，因此
 * <strong>不与</strong> {@link IamUserController}（{@code /fd/v1/users} + {@code USER_MANAGE}）合并：
 * 用户管理权限不应该顺带读到工单元数据，工单交接权限也不应该顺带改账号资料。
 * 两个控制器的路径前缀不同，方法级 {@code @PreAuthorize} 也覆盖不了对方的类级注解，
 * 分开是唯一正确的做法。</p>
 *
 * <p>返回体是 {@link AdminHandoffAssignmentResult}：只有编号、状态、当前负责人与版本。
 * 管理员即便同时持有 IT 角色，也只能通过其 IT 身份对应的普通工单接口看正文。</p>
 */
@RestController
@RequestMapping("/fd/v1/admin/users")
@PreAuthorize("hasAuthority('TICKET_ADMIN_HANDOFF')")
public class AdminUserHandoffController {

    private final IamUserService iamUserService;

    public AdminUserHandoffController(IamUserService iamUserService) {
        this.iamUserService = iamUserService;
    }

    /**
     * 查询目标用户当前负责的全部活动工单，供停用账号或替换角色时组织交接方案。
     *
     * <p>不分页：交接方案必须完整，分页会让客户端拿到半份方案再拼装；
     * 单次请求的项数就是该用户当前在办工单数，由 {@code handoffs} 的完整性规则兜底。
     * 用户不存在返回 {@code 404/USER_NOT_FOUND}，没有活动工单返回 {@code 200} 与空数组。</p>
     */
    @GetMapping("/{userId}/active-ticket-assignments")
    public R<List<AdminHandoffAssignmentResult>> activeTicketAssignments(
            @PathVariable long userId) {
        return R.success(iamUserService.activeTicketAssignments(userId));
    }
}
