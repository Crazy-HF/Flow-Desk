package com.flowdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketRecordRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketRecordMapper extends BaseMapper<TicketRecord> {
    /**
     * 根据 ticketId 分页查询 TicketRecord
     */
    @Select("SELECT r.id, r.ticket_id, r.sequence_no," +
            "               r.record_type, r.actor_type, r.actor_user_id," +
            "               actor.display_name AS actor_display_name," +
            "               r.content, r.reason," +
            "               r.from_status, r.to_status," +
            "               r.from_assignee_id, r.to_assignee_id," +
            "               r.from_category_id, r.to_category_id," +
            "               r.from_priority, r.to_priority," +
            "               r.deadline_at, r.completion_method," +
            "               r.close_method, r.close_reason, r.created_at" +
            " FROM ticket_record r" +
            " LEFT JOIN iam_user actor ON actor.id = r.actor_user_id" +
            " WHERE r.ticket_id = #{ticketId}" +
            " ORDER BY r.sequence_no ASC")
    Page<TicketRecordRow> selectTimelinePage(
            Page<TicketRecordRow> page,
            @Param("ticketId") long ticketId);
}
