package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.service.IamRolePermissionService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 角色与权限多对多关系 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Service
public class IamRolePermissionServiceImpl extends ServiceImpl<IamRolePermissionMapper, IamRolePermission> implements IamRolePermissionService {

}
