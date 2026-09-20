package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IamUserMapper extends BaseMapper<IamUser> {
}
