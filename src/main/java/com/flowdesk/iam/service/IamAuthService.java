package com.flowdesk.iam.service;

import com.flowdesk.iam.domain.bo.IamAuthBO;

public interface IamAuthService {
    /** 按登录名查询认证信息；用户不存在时返回 null。 */
    IamAuthBO findByUsername(String username);
}
