package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 预置权限 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamPermissionMapper extends BaseMapper<IamPermission> {

    /**根据ID查询权限，并加锁*/
    @Select("""
        SELECT id, code, name, description, created_at
        FROM iam_permission
        WHERE id = #{permissionId}
        FOR UPDATE
        """)
    IamPermission selectByIdForUpdate(
            @Param("permissionId") long permissionId
    );
}
