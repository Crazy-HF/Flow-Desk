package com.flowdesk.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.iam.domain.IamUserRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 用户与角色多对多关系 Mapper 接口
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@Mapper
public interface IamUserRoleMapper extends BaseMapper<IamUserRole> {

    /**
     * 根据用户ID查询角色ID列表
     */
    @Select("SELECT role_id FROM user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(Long userId);

    /**
     * 批量插入用户角色关系
     */
    @Insert({
            "<script>",
            "INSERT IGNORE INTO user_role (user_id, role_id) VALUES",
            "<foreach collection='roleIds' item='roleId' separator=','>",
            "(#{userId}, #{roleId})",
            "</foreach>",
            "</script>"
    })
    int batchInsertUserRoles(@Param("userId") Long userId,
                             @Param("roleIds") List<Long> roleIds);
}
