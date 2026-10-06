package com.flowdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /** 按用户与规范化提交键读取不可变创建结果，不查询其他用户的提交。 */
    @Select("""
            SELECT id, ticket_no, created_at
            FROM ticket
            WHERE requester_id = #{requesterId}
              AND submission_key = #{submissionKey}
            """)
    Ticket selectCreationByRequesterAndSubmissionKey(
            @Param("requesterId") long requesterId,
            @Param("submissionKey") String submissionKey);

    /** 权限与资源关系同时成立才能读取正文；不返回提交键。 */
    @Select("""
            SELECT t.id, t.ticket_no, t.title, t.description,
                   t.category_id, c.name AS category_name,
                   t.priority, t.status,
                   t.requester_id, requester.display_name AS requester_display_name,
                   t.assignee_id, assignee.display_name AS assignee_display_name,
                   t.action_deadline_at, t.version,
                   t.completion_method, t.close_method, t.close_reason, t.ended_at,
                   t.created_at, t.updated_at
            FROM ticket t
            JOIN ticket_category c ON c.id = t.category_id
            JOIN iam_user requester ON requester.id = t.requester_id
            LEFT JOIN iam_user assignee ON assignee.id = t.assignee_id
            WHERE t.ticket_no = #{ticketNo}
              AND (
                (#{canViewOwn} = TRUE AND t.requester_id = #{currentUserId})
                OR (#{canViewQueue} = TRUE AND t.status = 'PENDING')
                OR (#{canViewParticipated} = TRUE AND (
                    t.assignee_id = #{currentUserId}
                    OR EXISTS (
                        SELECT 1 FROM ticket_participant p
                        WHERE p.ticket_id = t.id AND p.user_id = #{currentUserId}
                    )
                ))
              )
            """)
    TicketDetailRow selectVisibleDetail(
            @Param("ticketNo") String ticketNo,
            @Param("currentUserId") long currentUserId,
            @Param("canViewOwn") boolean canViewOwn,
            @Param("canViewQueue") boolean canViewQueue,
            @Param("canViewParticipated") boolean canViewParticipated);

    /** 数据范围由 scope 决定，用户 ID 仅由应用服务的认证上下文提供。 */
    @Select("""
            <script>
            SELECT t.ticket_no, t.title,
                   t.category_id, c.name AS category_name,
                   t.priority, t.status,
                   t.requester_id, requester.display_name AS requester_display_name,
                   t.assignee_id, assignee.display_name AS assignee_display_name,
                   t.action_deadline_at, t.created_at, t.updated_at, t.version
            FROM ticket t
            JOIN ticket_category c ON c.id = t.category_id
            JOIN iam_user requester ON requester.id = t.requester_id
            LEFT JOIN iam_user assignee ON assignee.id = t.assignee_id
            <choose>
              <when test="query.scope != null and query.scope.name() == 'REQUESTED_BY_ME'">
                WHERE t.requester_id = #{currentUserId}
              </when>
              <when test="query.scope != null and query.scope.name() == 'PENDING_QUEUE'">
                WHERE t.status = 'PENDING'
              </when>
              <when test="query.scope != null and query.scope.name() == 'ASSIGNED_TO_ME'">
                WHERE t.assignee_id = #{currentUserId}
              </when>
              <when test="query.scope != null and query.scope.name() == 'PARTICIPATED_BY_ME'">
                WHERE EXISTS (
                  SELECT 1 FROM ticket_participant p
                  WHERE p.ticket_id = t.id AND p.user_id = #{currentUserId}
                )
              </when>
              <otherwise>
                WHERE 1 = 0
              </otherwise>
            </choose>
            <if test="query.status != null and !query.status.isEmpty()">
              AND t.status IN
              <foreach collection="query.status" item="value"
                       open="(" separator="," close=")">
                #{value}
              </foreach>
            </if>
            <if test="query.priority != null and !query.priority.isEmpty()">
              AND t.priority IN
              <foreach collection="query.priority" item="value"
                       open="(" separator="," close=")">
                #{value}
              </foreach>
            </if>
            <if test="query.categoryId != null">
              AND t.category_id = #{query.categoryId}
            </if>
            <if test="keywordPattern != null">
              AND (t.ticket_no LIKE #{keywordPattern} ESCAPE '!'
                   OR t.title LIKE #{keywordPattern} ESCAPE '!')
            </if>
            <if test="createdFrom != null">
              AND t.created_at &gt;= #{createdFrom}
            </if>
            <if test="createdTo != null">
              AND t.created_at &lt;= #{createdTo}
            </if>
            <choose>
              <when test="query.sort.name() == 'CREATED_DESC'">
                ORDER BY t.created_at DESC, t.id DESC
              </when>
              <when test="query.sort.name() == 'CREATED_ASC'">
                ORDER BY t.created_at ASC, t.id ASC
              </when>
              <when test="query.sort.name() == 'PRIORITY_DESC_CREATED_ASC'">
                ORDER BY CASE t.priority
                    WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC,
                    t.created_at ASC, t.id ASC
              </when>
              <otherwise>
                ORDER BY t.updated_at DESC, t.id DESC
              </otherwise>
            </choose>
            </script>
            """)
    Page<TicketListRow> selectScopedPage(
            Page<TicketListRow> page,
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketQuery query,
            @Param("keywordPattern") String keywordPattern,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /** 领取待处理工单。 */
    @Update("""
        UPDATE ticket
        SET status = 'PROCESSING',
            assignee_id = #{assigneeId},
            version = version + 1,
            record_seq = record_seq + 1,
            updated_at = #{now}
        WHERE id = #{ticketId}
          AND version = #{expectedVersion}
          AND status = 'PENDING'
          AND assignee_id IS NULL
          AND requester_id <> #{assigneeId}
        """)
    int claimPending(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("assigneeId") long assigneeId,
            @Param("now") LocalDateTime now);

    /** 条件更新失败后读取最新状态；锁定读避免事务快照返回旧版本。 */
    @Select("""
            SELECT id, status, version
            FROM ticket
            WHERE id = #{ticketId}
            FOR UPDATE
            """)
    Ticket selectClaimConflictSnapshotForUpdate(@Param("ticketId") long ticketId);


    /** 当前负责人在 PROCESSING 上追加处理记录：状态与负责人不变，只递增版本与记录序号。 */
    @Update("""
    UPDATE ticket
    SET version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int advanceAssigneeAction(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /** 当前负责人提交解决结果：进入待确认并写入确认期限。 */
    @Update("""
    UPDATE ticket
    SET status = 'WAITING_FOR_CONFIRMATION',
        action_deadline_at = #{deadlineAt},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int submitResolution(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("deadlineAt") LocalDateTime deadlineAt,
            @Param("now") LocalDateTime now);

    /** 提交人确认解决：进入终态，期限失效并记录结束时间与完成方式。 */
    @Update("""
    UPDATE ticket
    SET status = 'COMPLETED',
        action_deadline_at = NULL,
        completion_method = 'REQUESTER_CONFIRMED',
        ended_at = #{now},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'WAITING_FOR_CONFIRMATION'
      AND requester_id = #{actorId}
    """)
    int confirmResolution(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

}
