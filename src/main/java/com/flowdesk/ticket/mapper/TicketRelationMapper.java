package com.flowdesk.ticket.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.flowdesk.ticket.infrastructure.persistence.TicketRelationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 写入「后续工单」关联：source = 新建的后续工单，target = 被引用的原工单。
     *
     * <p>唯一键 {@code uk_ticket_relation_source_type(source_ticket_id, relation_type)}
     * 保证一张工单最多只有一条后续指向。只有创建工单会写这条关系，且同一 submissionKey
     * 重放时不再走创建路径，因此正常路径不会撞唯一键。</p>
     */
    @Insert("""
            INSERT INTO ticket_relation
                (source_ticket_id, target_ticket_id, relation_type, created_by, created_at)
            VALUES
                (#{sourceTicketId}, #{targetTicketId}, 'FOLLOW_UP', #{createdBy}, #{createdAt})
            """)
    int recordFollowUp(
            @Param("sourceTicketId") long sourceTicketId,
            @Param("targetTicketId") long targetTicketId,
            @Param("createdBy") long createdBy,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * 读取一张工单的全部有向关联，连同对端工单摘要。
     *
     * <p>方向相对入参工单：本单是 {@code source} 记 {@code OUTGOING}，本单是 {@code target}
     * 记 {@code INCOMING}。两个分支分别命中 {@code uk_ticket_relation_source_type} 与
     * {@code idx_ticket_relation_target_type_source}。</p>
     *
     * <p><b>刻意不在此处过滤对端可见性</b>：契约明确「关联摘要不代表目标工单详情权限」
     * （{@code docs/api-design.md} 7.5），打开对端时仍走目标工单自己的可见性校验。</p>
     *
     * <p>排序固定为后续关联在前、重复关联在后，组内按工单编号升序。
     * {@code relationOrder} 只用于排序，不映射到 {@link TicketRelationRow}。</p>
     */
    @Select("""
            SELECT r.relation_type AS relationType,
                   CASE r.relation_type WHEN 'FOLLOW_UP' THEN 1 ELSE 2 END AS relationOrder,
                   'OUTGOING'      AS direction,
                   t.ticket_no     AS ticketNo,
                   t.title         AS title,
                   t.status        AS status
            FROM ticket_relation r
            JOIN ticket t ON t.id = r.target_ticket_id
            WHERE r.source_ticket_id = #{ticketId}
            UNION ALL
            SELECT r.relation_type,
                   CASE r.relation_type WHEN 'FOLLOW_UP' THEN 1 ELSE 2 END,
                   'INCOMING',
                   t.ticket_no,
                   t.title,
                   t.status
            FROM ticket_relation r
            JOIN ticket t ON t.id = r.source_ticket_id
            WHERE r.target_ticket_id = #{ticketId}
            ORDER BY relationOrder, ticketNo
            """)
    List<TicketRelationRow> selectRelationsByTicketId(@Param("ticketId") long ticketId);
}
