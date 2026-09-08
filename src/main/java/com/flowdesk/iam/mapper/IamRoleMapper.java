package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 预置角色 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamRoleMapper extends BaseMapper<IamRole> {

}
