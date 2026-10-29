package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.infrastructure.persistence.TicketAiSuggestionSourceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 成功建议的来源快照；写入与建议及请求完成标记共用一个事务。 */
@Mapper
public interface TicketAiSuggestionSourceMapper {

    @Insert("""
            INSERT INTO ticket_ai_suggestion_source
                (suggestion_id, sequence_no, source_ticket_id, ticket_no, title, process_excerpt)
            VALUES (#{suggestionId}, #{sequenceNo}, #{sourceTicketId},
                    #{ticketNo}, #{title}, #{processExcerpt})
            """)
    int insert(TicketAiSuggestionSourceRow row);

    @Select("""
            SELECT suggestion_id AS suggestionId, sequence_no AS sequenceNo,
                   source_ticket_id AS sourceTicketId, ticket_no AS ticketNo,
                   title, process_excerpt AS processExcerpt
            FROM ticket_ai_suggestion_source
            WHERE suggestion_id = #{suggestionId}
            ORDER BY sequence_no
            """)
    List<TicketAiSuggestionSourceRow> selectBySuggestionId(
            @Param("suggestionId") long suggestionId);
}
