package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /**
     * 根据角色ID查询角色信息，并加锁
     * 更新和删除在同一事务里先锁角色，可与后续授权关系写操作保持“角色优先”的锁顺序
     */
    @Select("""
        SELECT id, code, name, description, created_at
        FROM iam_role
        WHERE id = #{roleId}
        FOR UPDATE
        """)
    IamRole selectByIdForUpdate(@Param("roleId") long roleId);

    @Select("""
        <script>
        SELECT id, code, name, description, created_at
        FROM iam_role
        WHERE id IN
        <foreach collection="roleIds"
                 item="roleId"
                 open="("
                 separator=","
                 close=")">
            #{roleId}
        </foreach>
        ORDER BY id
        FOR UPDATE
        </script>
        """)
    List<IamRole> selectByIdsForUpdate(
            @Param("roleIds") List<Long> roleIds
    );

    /**
     * 按角色编码查询角色并加锁。
     *
     * <p>用于会影响“最后启用管理员”的账号启停用例：必须先锁 {@code SYSTEM_ADMIN} 角色行，
     * 再锁目标用户，锁顺序见 {@code docs/modules/rbac.md} 第 7 节。</p>
     */
    @Select("""
        SELECT id, code, name, description, created_at
        FROM iam_role
        WHERE code = #{code}
        FOR UPDATE
        """)
    IamRole selectByCodeForUpdate(@Param("code") String code);
}
