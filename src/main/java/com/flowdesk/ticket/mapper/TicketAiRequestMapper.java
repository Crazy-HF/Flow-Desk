package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.infrastructure.persistence.TicketAiRequestRow;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketAiRequestMapper {

    /**
     * 按用户和请求标识查找旧请求，供重复提交时核对输入并避免再次调用模型。
     * Service 还须核对工单 ID、版本和五项输入的哈希。
     */
    @Select("""
            SELECT id,
                   ticket_id AS ticketId,
                   user_id AS userId,
                   request_id AS requestId,
                   ticket_version AS ticketVersion,
                   input_sha256 AS inputSha256,
                   status,
                   failure_code AS failureCode,
                   created_at AS createdAt,
                   finished_at AS finishedAt
            FROM ticket_ai_request
            WHERE user_id = #{userId}
              AND request_id = #{requestId}
            """)
    TicketAiRequestRow selectByUserAndRequestId(
            @Param("userId") long userId,
            @Param("requestId") String requestId);

    /**
     * 登记 RESERVED 请求；同一用户的 requestId 由唯一键限制只能登记一次。
     * 并发重复请求只有一个能插入，调用方须检查返回值。
     */
    @Insert("""
            INSERT INTO ticket_ai_request
                (ticket_id, user_id, request_id, ticket_version,
                 input_sha256, status, failure_code, created_at, finished_at)
            VALUES
                (#{ticketId}, #{userId}, #{requestId}, #{ticketVersion},
                 #{inputSha256}, 'RESERVED', NULL, #{createdAt}, NULL)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReserved(TicketAiRequestRow row);

    /**
     * 仅将 RESERVED 请求结束为指定状态；返回 1 表示成功，0 表示状态已变化。
     * 保存成功历史与标记 SUCCEEDED 必须处于同一个短事务。
     */
    @Update("""
            UPDATE ticket_ai_request
            SET status = #{status},
                failure_code = #{failureCode},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND status = 'RESERVED'
            """)
    int finishIfReserved(
            @Param("id") long id,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 将超过模型调用最大预期时长的遗留占位请求收敛为结果不确定。
     * 状态条件确保不会覆盖已经保存成功或正常失败的结果。
     */
    @Update("""
            UPDATE ticket_ai_request
            SET status = 'UNKNOWN',
                failure_code = 'AI_RESULT_UNCERTAIN',
                finished_at = #{finishedAt}
            WHERE status = 'RESERVED'
              AND created_at < #{cutoff}
            LIMIT #{limit}
            """)
    int resolveAbandonedReservations(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("limit") int limit);

    /**
     * 找出结束时间已超过 30 天的工单所关联的请求 ID。
     * 按请求 ID 固定顺序取一批；未结束工单不会命中。
     */
    @Select("""
        SELECT r.id
        FROM ticket_ai_request r
        JOIN ticket t ON t.id = r.ticket_id
        WHERE t.ended_at IS NOT NULL
          AND t.ended_at <= #{cutoff}
        ORDER BY r.id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredRequestIds(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /**
     * 删除一批已清空建议历史的请求元数据。
     * 调用方必须先删除对应 suggestion，并在同一事务中执行。
     */
    @Delete("""
        <script>
        DELETE FROM ticket_ai_request
        WHERE id IN
        <foreach collection="ids" item="id"
                 open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int deleteByIds(@Param("ids") List<Long> ids);
}
