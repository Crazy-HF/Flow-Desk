package com.flowdesk.ticket.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiSuggestionRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 保存和读取受限的 AI 建议历史；授权和保留期由应用服务校验。 */
@Mapper
public interface TicketAiSuggestionMapper {

    /**
     * 保存通过二次工单复核的建议；调用方须在同一事务内完成请求状态更新。
     * V14 的外键保证建议与请求属于同一工单。
     */
    @Insert("""
            INSERT INTO ticket_ai_suggestion
                (request_row_id, ticket_id,
                 input_title, input_description, input_category,
                 input_priority, input_status,
                 summary, questions_json, suggested_steps_json,
                 model, prompt_version, input_tokens, output_tokens,
                 created_at)
            VALUES
                (#{requestRowId}, #{ticketId},
                 #{inputTitle}, #{inputDescription}, #{inputCategory},
                 #{inputPriority}, #{inputStatus},
                 #{summary}, #{questionsJson}, #{suggestedStepsJson},
                 #{model}, #{promptVersion}, #{inputTokens}, #{outputTokens},
                 #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSuggestion(TicketAiSuggestionRow row);

    /**
     * 读取指定成功请求的建议；本方法不负责工单可见性及负责人授权。
     */
    @Select("""
            SELECT s.id,
                   s.request_row_id AS requestRowId,
                   s.ticket_id AS ticketId,
                   r.request_id AS requestId,
                   s.input_title AS inputTitle,
                   s.input_description AS inputDescription,
                   s.input_category AS inputCategory,
                   s.input_priority AS inputPriority,
                   s.input_status AS inputStatus,
                   s.summary,
                   CAST(s.questions_json AS CHAR) AS questionsJson,
                   CAST(s.suggested_steps_json AS CHAR) AS suggestedStepsJson,
                   s.model,
                   s.prompt_version AS promptVersion,
                   s.input_tokens AS inputTokens,
                   s.output_tokens AS outputTokens,
                   s.created_at AS createdAt
            FROM ticket_ai_suggestion s
            JOIN ticket_ai_request r ON r.id = s.request_row_id
            WHERE s.request_row_id = #{requestRowId}
            """)
    TicketAiSuggestionRow selectByRequestRowId(
            @Param("requestRowId") long requestRowId);

    /**
     * 按工单分页读取建议历史；调用方须先检查目标工单及保留期。
     * 来源失权的整条建议在 SQL WHERE 中排除，分页插件的总数与列表口径一致。
     * 创建时间及 ID 倒序确保稳定分页；JSON 列作为字符串交给应用层解析。
     */
    @Select("""
            SELECT s.id,
                   s.request_row_id AS requestRowId,
                   s.ticket_id AS ticketId,
                   r.request_id AS requestId,
                   s.input_title AS inputTitle,
                   s.input_description AS inputDescription,
                   s.input_category AS inputCategory,
                   s.input_priority AS inputPriority,
                   s.input_status AS inputStatus,
                   s.summary,
                   CAST(s.questions_json AS CHAR) AS questionsJson,
                   CAST(s.suggested_steps_json AS CHAR) AS suggestedStepsJson,
                   s.model,
                   s.prompt_version AS promptVersion,
                   s.input_tokens AS inputTokens,
                   s.output_tokens AS outputTokens,
                   s.created_at AS createdAt
            FROM ticket_ai_suggestion s
            JOIN ticket_ai_request r ON r.id = s.request_row_id
            WHERE s.ticket_id = #{ticketId}
              AND NOT EXISTS (
                  SELECT 1
                  FROM ticket_ai_suggestion_source source
                  JOIN ticket referenced ON referenced.id = source.source_ticket_id
                  WHERE source.suggestion_id = s.id
                    AND NOT (
                        (#{canViewOwn} = TRUE AND referenced.requester_id = #{currentUserId})
                        OR (#{canViewParticipated} = TRUE AND (
                            referenced.assignee_id = #{currentUserId}
                            OR EXISTS (
                                SELECT 1 FROM ticket_participant participant
                                WHERE participant.ticket_id = referenced.id
                                  AND participant.user_id = #{currentUserId}
                            )
                        ))
                    )
              )
            ORDER BY s.created_at DESC, s.id DESC
            """)
    Page<TicketAiSuggestionRow> selectTicketPage(
            Page<TicketAiSuggestionRow> page,
            @Param("ticketId") long ticketId,
            @Param("currentUserId") long currentUserId,
            @Param("canViewOwn") boolean canViewOwn,
            @Param("canViewParticipated") boolean canViewParticipated);

    /**
     * 按请求 ID 删除建议历史。必须先于对应请求元数据删除，
     * 否则会触发 V14 的外键约束。
     */
    @Delete("""
        <script>
        DELETE FROM ticket_ai_suggestion
        WHERE request_row_id IN
        <foreach collection="ids" item="id"
                 open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int deleteByRequestIds(@Param("ids") List<Long> ids);
}
