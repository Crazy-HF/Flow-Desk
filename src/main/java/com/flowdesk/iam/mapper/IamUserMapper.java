package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.bo.IamAuthenticationBO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IamUserMapper extends BaseMapper<IamUser> {

    IamAuthenticationBO selectAuthenticationByUsername(@Param("username") String username);
}
