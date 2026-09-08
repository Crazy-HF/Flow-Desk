package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.service.IamUserRoleService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户与角色多对多关系 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Service
public class IamUserRoleServiceImpl extends ServiceImpl<IamUserRoleMapper, IamUserRole> implements IamUserRoleService {

}
