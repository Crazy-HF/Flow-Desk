package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserStatus;
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

    @Select("""
        <script>
        SELECT u.id,u.username,u.display_name,u.status,u.created_at,u.updated_at,u.version
        FROM iam_user u
        <where>
            <if test="keyword != null and keyword != ''">
                AND (
                    u.username LIKE CONCAT('%', #{keyword}, '%')
                    OR u.display_name LIKE CONCAT('%', #{keyword}, '%')
                )
            </if>

            <if test="status != null">
                AND u.status = #{status}
            </if>

            <if test="roleId != null">
                AND EXISTS (
                    SELECT 1
                    FROM iam_user_role ur
                    WHERE ur.user_id = u.id
                      AND ur.role_id = #{roleId}
                )
            </if>
        </where>
        </script>
        """)
    Page<IamUser> selectUserPage(
            Page<IamUser> page,
            @Param("keyword") String keyword,
            @Param("status") IamUserStatus status,
            @Param("roleId") Long roleId
    );
}
