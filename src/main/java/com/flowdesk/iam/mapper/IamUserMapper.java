package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IamUserMapper extends BaseMapper<IamUser> {
    @Select("""
        SELECT id, username, display_name, password, status,
               created_at, updated_at, version
        FROM iam_user
        WHERE id = #{userId}
        FOR UPDATE
        """)
    IamUser selectByIdForUpdate(@Param("userId") long userId);

    /**根据用户ID查询用户  查询资料字段*/
    @Select("""
        SELECT id, username, display_name, status
        FROM iam_user
        WHERE id = #{userId}
        """)
    IamUser selectProfileById(@Param("userId") long userId);

    @Select("""
        <script>
        SELECT id, username, display_name, password, status,
               created_at, updated_at, version
        FROM iam_user
        WHERE id IN
        <foreach collection="userIds"
                 item="userId"
                 open="("
                 separator=","
                 close=")">
            #{userId}
        </foreach>
        ORDER BY id
        FOR UPDATE
        </script>
        """)
    List<IamUser> selectByIdsForUpdate(@Param("userIds") List<Long> userIds);
}
