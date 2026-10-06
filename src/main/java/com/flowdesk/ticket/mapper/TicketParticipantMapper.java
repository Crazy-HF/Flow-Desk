package com.flowdesk.ticket.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TicketParticipantMapper {

    @Insert("""
            INSERT INTO ticket_participant
                (ticket_id, user_id, first_assigned_at, last_assigned_at)
            VALUES
                (#{ticketId}, #{userId}, #{assignedAt}, #{assignedAt})
            ON DUPLICATE KEY UPDATE
                last_assigned_at = VALUES(last_assigned_at)
            """)
    int recordAssignment(
            @Param("ticketId") long ticketId,
            @Param("userId") long userId,
            @Param("assignedAt") LocalDateTime assignedAt);
}