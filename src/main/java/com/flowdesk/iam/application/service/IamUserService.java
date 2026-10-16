package com.flowdesk.iam.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.command.*;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.query.UserQuery;
import jakarta.validation.Valid;

import java.util.List;


public interface IamUserService {

    /**
     * 写入新的密码摘要（已哈希）。
     *
     * <p>只负责持久化与版本条件更新；原密码校验、撤销会话由调用方编排。
     * 目标不存在或版本已被其他操作改动时返回 {@code false}。</p>
     */
    boolean updatePassword(long userId, String encodedPassword);

    /**按条件分页查询用户。*/
    PageResult<UserResult> page(UserQuery query);

    /** 根据用户ID获取用户信息。*/
    UserResult getById(long userId);

    /** 创建用户。*/
    UserResult create(CreateUserCommand request);

    /** 更新用户。*/
    UserResult update(long userId, UpdateUserCommand request);

    /** 启用用户。*/
    UserResult enable(long userId, UserStatusChangeCommand request);

    /** 禁用用户。*/
    UserResult disable(long userId,  UserStatusChangeCommand request);

    /** 替换用户角色。*/
    UserResult replaceRoles(long userId,  ReplaceUserRolesCommand request);

    /**
     * 查询目标用户当前负责的活动工单最小元数据（管理性交接读取侧，{@code docs/api-design.md} 8.3）。
     *
     * <p>只返回工单编号、状态、当前负责人与版本，按工单编号升序；不返回标题、问题描述、处理记录或附件。
     * 目标用户不存在时返回 {@code 404/USER_NOT_FOUND}；没有任何活动工单时返回空列表，不是错误。</p>
     */
    List<AdminHandoffAssignmentResult> activeTicketAssignments(long userId);

    /**
     * 管理员重置指定用户密码。
     */
    void resetPassword(long userId, ResetUserPasswordCommand request);



}
