package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IamUserRoleMapper extends BaseMapper<IamUserRole> {
    /**
     * 根据用户ID查询用户角色
     * @param userId
     * @return
     */
    @Select("""
        SELECT user_id, role_id, granted_by, granted_at
        FROM iam_user_role
        WHERE user_id = #{userId}
        ORDER BY role_id
        FOR UPDATE
        """)
    List<IamUserRole> selectByUserIdForUpdate(
            @Param("userId") long userId
    );

    /**
     * 根据角色ID查询用户角色
     * @param roleId
     * @return
     */
    @Select("""
    SELECT user_id, role_id, granted_by, granted_at
    FROM iam_user_role
    WHERE role_id = #{roleId}
    ORDER BY user_id
    FOR UPDATE
    """)
    List<IamUserRole> selectByRoleIdForUpdate(
            @Param("roleId") long roleId
    );

    @Select("""
        <script>
        SELECT user_id, role_id, granted_by, granted_at
        FROM iam_user_role
        WHERE role_id = #{roleId}
          AND user_id IN
        <foreach collection="userIds"
                 item="userId"
                 open="("
                 separator=","
                 close=")">
            #{userId}
        </foreach>
        ORDER BY user_id
        FOR UPDATE
        </script>
        """)
    List<IamUserRole> selectByRoleIdAndUserIdsForUpdate(
            @Param("roleId") long roleId,
            @Param("userIds") List<Long> userIds
    );
}
