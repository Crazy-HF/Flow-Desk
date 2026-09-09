package com.flowdesk.iam.service;

import com.flowdesk.iam.domain.IamUserRole;
import com.baomidou.mybatisplus.spring.service.IService;
import com.flowdesk.iam.domain.bo.IamUserRoleBO;
import com.flowdesk.iam.domain.vo.GrantURVO;
import com.flowdesk.iam.domain.vo.IamUserRoleVO;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;

import java.util.List;

/**
 * <p>
 * 用户与角色多对多关系 服务类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
public interface IamUserRoleService extends IService<IamUserRole> {

    /**
     * 按用户或角色分页查询授权关系。
     */
    PageResult<IamUserRoleBO> getUserRolePage(IamUserRoleVO userRoleVO, PageQuery pageQuery);

    /**
     * 为用户授予角色。
     */
    Boolean grantRole(GrantURVO grantURVO);

    /**
     * 撤销用户角色。
     */
    Boolean revokeRoles(Long userId, List<Long> roleIds);

    Boolean revokeRole(Long userId, Long roleId);
}
