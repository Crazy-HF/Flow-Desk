package com.flowdesk.auth.service;

import com.flowdesk.auth.domain.bo.AuthLoginResultBO;
import com.flowdesk.auth.domain.vo.AuthVO;

/**
 * 登录用例的服务入口。
 *
 * <p>编排 IAM 身份查询、密码校验、Redis 会话和登录令牌创建。</p>
 */
public interface AuthLoginService {


    /**
     *
     */
    AuthLoginResultBO login(AuthVO authVO);
}
