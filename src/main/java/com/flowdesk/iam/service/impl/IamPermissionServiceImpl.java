package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.service.IamPermissionService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 预置权限 服务实现类
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Service
public class IamPermissionServiceImpl extends ServiceImpl<IamPermissionMapper, IamPermission> implements IamPermissionService {

}
