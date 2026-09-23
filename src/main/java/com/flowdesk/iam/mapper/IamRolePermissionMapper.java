package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 角色与权限多对多关系 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamRolePermissionMapper extends BaseMapper<IamRolePermission> {

    /**
     * 根据角色ID和权限ID查询角色权限
     * @param roleId
     * @param permissionId
     * @return
     */
    @Select("""
    SELECT role_id, permission_id, granted_by, granted_at
    FROM iam_role_permission
    WHERE role_id = #{roleId}
      AND permission_id = #{permissionId}
    FOR UPDATE
    """)
    IamRolePermission selectByKeyForUpdate(
            @Param("roleId") long roleId,
            @Param("permissionId") long permissionId
    );

    @Select("""
        SELECT role_id, permission_id, granted_by, granted_at
        FROM iam_role_permission
        WHERE role_id = #{roleId}
        ORDER BY permission_id
        FOR UPDATE
        """)
    List<IamRolePermission> selectByRoleIdForUpdate(
            @Param("roleId") long roleId
    );
}
