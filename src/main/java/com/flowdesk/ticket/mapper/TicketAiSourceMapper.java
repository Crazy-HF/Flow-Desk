package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.infrastructure.persistence.TicketAiSourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 建议的已结工单来源召回。调用方必须从认证上下文提供用户 ID 和实际权限，
 * 并在发送模型前再次复核来源；本查询本身不授权模型外发。
 */
@Mapper
public interface TicketAiSourceMapper {

    /**
     * 只取同类、当前用户按详情规则可见且有非空处理记录的已结工单。
     * 来源按结束时间及 ID 倒序稳定排序；无处理正文的工单不进入结果。
     * 队列可见分支只覆盖 PENDING，对这里的三种终态不适用。
     */
    @Select("""
            SELECT t.id AS ticketId,
                   t.ticket_no AS ticketNo,
                   t.title,
                   t.ended_at AS endedAt,
                   (
                       SELECT r.content
                       FROM ticket_record r
                       WHERE r.ticket_id = t.id
                         AND r.record_type = 'PROCESS'
                         AND NULLIF(TRIM(r.content), '') IS NOT NULL
                       ORDER BY r.sequence_no DESC
                       LIMIT 1
                   ) AS processContent
            FROM ticket t
            WHERE t.category_id = #{categoryId}
              AND t.status IN ('COMPLETED', 'CANCELED', 'CLOSED')
              AND t.ended_at IS NOT NULL
              AND t.id <> #{currentTicketId}
              AND (
                  (#{canViewOwn} = TRUE AND t.requester_id = #{currentUserId})
                  OR (#{canViewParticipated} = TRUE AND (
                      t.assignee_id = #{currentUserId}
                      OR EXISTS (
                          SELECT 1 FROM ticket_participant p
                          WHERE p.ticket_id = t.id
                            AND p.user_id = #{currentUserId}
                      )
                  ))
              )
              AND EXISTS (
                  SELECT 1 FROM ticket_record r
                  WHERE r.ticket_id = t.id
                    AND r.record_type = 'PROCESS'
                    AND NULLIF(TRIM(r.content), '') IS NOT NULL
              )
            ORDER BY t.ended_at DESC, t.id DESC
            LIMIT 5
            """)
    List<TicketAiSourceRow> selectVisibleEndedSources(
            @Param("currentTicketId") long currentTicketId,
            @Param("categoryId") long categoryId,
            @Param("currentUserId") long currentUserId,
            @Param("canViewOwn") boolean canViewOwn,
            @Param("canViewParticipated") boolean canViewParticipated);
}
