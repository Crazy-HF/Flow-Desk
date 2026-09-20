package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IamUserRoleMapper extends BaseMapper<IamUserRole> {

}
