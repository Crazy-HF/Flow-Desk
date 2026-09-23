package com.flowdesk.iam.application.service;

import com.flowdesk.iam.application.result.AuthenticationSnapshot;
import com.flowdesk.iam.application.result.UserProfileSnapshot;

public interface IamAuthService {
    /** 按登录名查询认证信息；用户不存在时返回 null。 */
    AuthenticationSnapshot findByUsername(String username);

    /**查询认证响应所需的最新用户资料；不存在时返回 null。*/
    UserProfileSnapshot findProfileById(long userId);
}
