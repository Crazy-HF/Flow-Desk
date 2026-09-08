package com.flowdesk.iam.service;

import com.flowdesk.iam.domain.bo.IamUserBO;
import com.flowdesk.iam.domain.vo.IamUserCreateVO;
import com.flowdesk.iam.domain.vo.IamUserResetPasswordVO;
import com.flowdesk.iam.domain.vo.IamUserVO;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;

public interface IamUserService {
    /**
     * 获取IAM用户列表
     * @return 安全的分页用户列表
     */
    PageResult<IamUserBO> listIamUsers(IamUserVO iamUserVO, PageQuery pageQuery);

    /**
     * 根据用户ID获取IAM用户
     */
    IamUserBO getIamUserById(Long userId);

    /**
     * 创建IAM用户
     */
    IamUserBO createIamUser(IamUserCreateVO iamUserVO);

    /**
     * 更新IAM用户
     */
    IamUserBO updateIamUser(Long userId, IamUserVO iamUserVO);

    /**
     * 启用用户账号
     */
    void enableIamUser(Long userId, Long expectedVersion);

    /**
     * 停用用户账号，保留历史身份和业务记录。
     */
    void disableIamUser(Long userId, Long expectedVersion);

    /**
     * 重置用户密码并撤销会话
     */
    void resetIamUserPassword(Long userId, IamUserResetPasswordVO request);
}
