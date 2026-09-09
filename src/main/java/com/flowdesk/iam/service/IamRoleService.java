package com.flowdesk.iam.service;

import com.flowdesk.iam.domain.IamRole;
import com.baomidou.mybatisplus.spring.service.IService;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamCrateRoleVO;
import com.flowdesk.iam.domain.vo.IamRoleVO;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 预置角色 服务类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
public interface IamRoleService extends IService<IamRole> {

    /**
     *
     */
    PageResult<IamRoleBO> getRoleList(IamRoleVO iamRoleVO, PageQuery pageQuery);

    /**
     * 根据角色ID获取角色信息
     */
    IamRoleBO getIamRoleById(Long roleId);

    /**
     * 根据角色Ids获取角色信息
     */
    List<IamRoleBO> getIamRoleByIds(List<Long> roleIds);

    /**
     * 创建角色
     */
    IamRoleBO createRole(@Valid IamCrateRoleVO iamRoleVO);

    /**
     * 修改角色
     */
    IamRoleBO updateRole(Long roleId, @Valid IamRoleVO iamRoleVO);

    /**
     * 删除角色
     */
    Void deleteRole(Long roleId);
}
