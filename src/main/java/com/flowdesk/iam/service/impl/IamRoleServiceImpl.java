package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.service.IamRoleService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 预置角色 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Service
public class IamRoleServiceImpl extends ServiceImpl<IamRoleMapper, IamRole> implements IamRoleService {

}
