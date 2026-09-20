package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 预置权限 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamPermissionMapper extends BaseMapper<IamPermission> {

}
