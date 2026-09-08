package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户与角色多对多关系 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamUserRoleMapper extends BaseMapper<IamUserRole> {

}
