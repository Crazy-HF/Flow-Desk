package com.flowdesk.ticket.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工单有向关联（{@code ticket_relation}）。
 *
 * <p>本表自建库迁移预置后一直没有写入方：阶段 2 不发送 {@code sourceTicketNo}，
 * 片 A～片 C 的动作也不产生关联。片 D 的「重复工单」关闭是第一个写入场景。</p>
 */
@Mapper
public interface TicketRelationMapper {

    /**
     * 写入「本工单与另一张工单重复」的有向关联：source = 被关闭的工单，target = 有效的那张。
     *
     * <p>唯一键 {@code uk_ticket_relation_source_type(source_ticket_id, relation_type)}
     * 保证一张工单只有一条重复指向。正常路径下第二次关闭会先在条件更新上失败
     * （已关闭是终态，版本也已递增），不会走到这里。</p>
     */
    @Insert("""
            INSERT INTO ticket_relation
                (source_ticket_id, target_ticket_id, relation_type, created_by, created_at)
            VALUES
                (#{sourceTicketId}, #{targetTicketId}, 'DUPLICATE', #{createdBy}, #{createdAt})
            """)
    int recordDuplicate(
            @Param("sourceTicketId") long sourceTicketId,
            @Param("targetTicketId") long targetTicketId,
            @Param("createdBy") long createdBy,
            @Param("createdAt") LocalDateTime createdAt);
}
