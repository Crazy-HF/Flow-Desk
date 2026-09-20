package com.flowdesk.iam.service;

import com.flowdesk.iam.domain.IamUser;

import java.util.List;

public interface IamUserService {

    /**
     * 写入新的密码摘要（已哈希）。
     *
     * <p>只负责持久化与版本条件更新；原密码校验、撤销会话由调用方编排。
     * 目标不存在或版本已被其他操作改动时返回 {@code false}。</p>
     */
    boolean updatePassword(long userId, String encodedPassword);

}
