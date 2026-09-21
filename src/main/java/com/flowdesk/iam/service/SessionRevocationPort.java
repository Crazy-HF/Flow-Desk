package com.flowdesk.iam.service;

/**
 * IAM 发起的会话撤销出口。
 *
 * <p>实现异常直接向上抛出，由调用方事务决定是否回滚。</p>
 */
public interface SessionRevocationPort {

    /**
     * 撤销指定用户的全部登录会话。
     *
     * @param userId 用户 ID
     */
    void revokeAll(long userId);
}