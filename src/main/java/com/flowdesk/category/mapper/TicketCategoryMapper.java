package com.flowdesk.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.category.domain.TicketCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工单分类数据访问接口。
 */
@Mapper
public interface TicketCategoryMapper
        extends BaseMapper<TicketCategory> {

    /**
     * 按主键查询分类并加锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>管理端的写用例（修改、启停、删除）都在同一事务里先锁目标行：锁内再判版本，
     * 并发的"改名字 / 停用 / 删除"就只有一个能先通过版本判定，另一个拿到
     * {@code 409/CATEGORY_CONFLICT}。删除额外受益于这次加锁——外键在插入子行时会对
     * 父行取共享锁，所以拿到排他锁后不会再有新工单引用到这张分类。</p>
     *
     * <p>列名按 {@code sort_order → sortOrder} 的驼峰映射回填，与 {@code IamRoleMapper}
     * 一样依赖 MyBatis 的下划线转驼峰配置。（在 Java 文本块里写完整个 SELECT 是必要的：
     * 主键、名称与状态三列都要参与判定，不能只查 id。）</p>
     *
     * @param categoryId 分类 ID
     * @return 分类行；不存在时返回 {@code null}
     */
    @Select("""
        SELECT id, name, status, sort_order, created_at, updated_at, version
        FROM ticket_category
        WHERE id = #{categoryId}
        FOR UPDATE
        """)
    TicketCategory selectByIdForUpdate(@Param("categoryId") long categoryId);
}
