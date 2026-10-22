package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardCodeCountRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardRefCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据概览的聚合查询（{@code docs/api-design.md} 8.5）。
 *
 * <p><strong>为什么是独立 Mapper</strong>：它是纯读的聚合路径，与 {@code TicketMapper} 里那些
 * 条件更新和详情投影没有共用代码；放在一起会让"哪条语句会被事务写路径调用"更难判断。</p>
 *
 * <p><strong>为什么范围谓词只写一份</strong>：总数与四组必须基于同一个集合 {@code S}，
 * 五个语句各写一遍谓词迟早会漂移，而漂移的表现是"四组之和不等于总数"——一种很难在页面上
 * 一眼看出的错误。因此这里用一个常量承载"范围 + 时间 + 分类"的完整条件，
 * 五条语句只替换投影、{@code GROUP BY} 与 {@code ORDER BY}。</p>
 *
 * <p>谓词与 {@code TicketMapper.selectScopedPage} 的 {@code <choose>} 分支逐字对应：
 * {@code PENDING_QUEUE} 只取 {@code status='PENDING'}；{@code ASSIGNED_TO_ME} 取
 * {@code assignee_id = 当前用户}（含保留最后负责人的终态）；{@code PARTICIPATED_BY_ME} 用
 * {@code ticket_participant} 的 {@code EXISTS} 判定，避免一次转交产生重复计数。
 * <strong>列表范围语义变化时必须同步检查这里。</strong></p>
 *
 * <p>聚合全部在 MySQL 内完成，不把工单行取回 Java 再计数；五个查询由服务层放在同一个只读事务里，
 * 从而读到同一个一致性快照。</p>
 */
@Mapper
public interface TicketDashboardMapper {

    /**
     * 五条语句共用的筛选条件：可见范围 + UTC 创建时间闭区间 + 可选分类。
     *
     * <p>{@code scope} 由服务层保证非空且属于三个 IT 范围；{@code otherwise} 仍写成
     * {@code 1 = 0}，与列表 SQL 一样，让"范围不合法"退化成空结果而不是全表统计。</p>
     */
    String SCOPED_FILTER = """
            WHERE
            <choose>
              <when test="query.scope != null and query.scope.name() == 'PENDING_QUEUE'">
                t.status = 'PENDING'
              </when>
              <when test="query.scope != null and query.scope.name() == 'ASSIGNED_TO_ME'">
                t.assignee_id = #{currentUserId}
              </when>
              <when test="query.scope != null and query.scope.name() == 'PARTICIPATED_BY_ME'">
                EXISTS (
                  SELECT 1 FROM ticket_participant participant
                  WHERE participant.ticket_id = t.id
                    AND participant.user_id = #{currentUserId}
                )
              </when>
              <otherwise>
                1 = 0
              </otherwise>
            </choose>
            <if test="createdFrom != null">
              AND t.created_at &gt;= #{createdFrom}
            </if>
            <if test="createdTo != null">
              AND t.created_at &lt;= #{createdTo}
            </if>
            <if test="query.categoryId != null">
              AND t.category_id = #{query.categoryId}
            </if>
            """;

    /** 集合 {@code S} 的大小：总数有自己的来源，不由四组求和推导。 */
    @Select("<script>SELECT COUNT(*) FROM ticket t " + SCOPED_FILTER + "</script>")
    long countScoped(
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketDashboardQuery query,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /** 状态分组。输出顺序由服务层按状态机顺序固定，SQL 不承担展示次序。 */
    @Select("<script>"
            + "SELECT t.status AS code, COUNT(*) AS total FROM ticket t "
            + SCOPED_FILTER
            + "GROUP BY t.status"
            + "</script>")
    List<TicketDashboardCodeCountRow> countByStatus(
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketDashboardQuery query,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /** 优先级分组。输出顺序由服务层固定为 HIGH → MEDIUM → LOW。 */
    @Select("<script>"
            + "SELECT t.priority AS code, COUNT(*) AS total FROM ticket t "
            + SCOPED_FILTER
            + "GROUP BY t.priority"
            + "</script>")
    List<TicketDashboardCodeCountRow> countByPriority(
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketDashboardQuery query,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /**
     * 分类分组：名称取当前分类行，因此分类改名后显示新名字、停用后仍计入。
     *
     * <p>次序由 SQL 固定：数量降序、再按 ID 升序（同数量时小 ID 在前，界面每次刷新位置不变）。</p>
     */
    @Select("<script>"
            + "SELECT t.category_id AS ref_id, category.name AS display_name, COUNT(*) AS total "
            + "FROM ticket t "
            + "JOIN ticket_category category ON category.id = t.category_id "
            + SCOPED_FILTER
            + "GROUP BY t.category_id, category.name "
            + "ORDER BY COUNT(*) DESC, t.category_id ASC"
            + "</script>")
    List<TicketDashboardRefCountRow> countByCategory(
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketDashboardQuery query,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /**
     * 负责人分组：未分配是 {@code assignee_id IS NULL} 的那一组，同数量时排在最后。
     *
     * <p>{@code ORDER BY ... t.assignee_id IS NULL, t.assignee_id} 是 MySQL 的稳定写法：
     * 空值组表达式为 1、有 ID 的组为 0，因此升序即"有 ID 的在前"；同数量时再按 ID 升序。</p>
     */
    @Select("<script>"
            + "SELECT t.assignee_id AS ref_id, assignee.display_name AS display_name, COUNT(*) AS total "
            + "FROM ticket t "
            + "LEFT JOIN iam_user assignee ON assignee.id = t.assignee_id "
            + SCOPED_FILTER
            + "GROUP BY t.assignee_id, assignee.display_name "
            + "ORDER BY COUNT(*) DESC, t.assignee_id IS NULL, t.assignee_id ASC"
            + "</script>")
    List<TicketDashboardRefCountRow> countByAssignee(
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketDashboardQuery query,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);
}
