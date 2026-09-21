package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
