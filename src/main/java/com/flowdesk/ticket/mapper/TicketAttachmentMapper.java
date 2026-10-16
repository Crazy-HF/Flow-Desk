package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.infrastructure.persistence.TicketAttachmentRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 工单附件绑定（{@code ticket_attachment}）：通用附件与工单记录的一对一归属。 */
@Mapper
public interface TicketAttachmentMapper {

    /** 绑定一个附件到工单记录；{@code uk_ticket_attachment_attachment} 保证一份附件只绑一次。 */
    @Insert("""
            INSERT INTO ticket_attachment (attachment_id, record_id, created_at)
            VALUES (#{attachmentId}, #{recordId}, #{createdAt})
            """)
    int insertBinding(
            @Param("attachmentId") long attachmentId,
            @Param("recordId") long recordId,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * 复核可引用的附件并对齐锁定：必须属于当前用户、时间在保留期内、尚未被任何记录绑定。
     *
     * <p>{@code FOR UPDATE} 锁住 attachment 行：并发引用同一附件时只有一个事务通过，
     * 另一个在此等待后看到已绑定而失败——错误收敛为 400，而不是撞唯一键落 500。</p>
     *
     * <p>返回可用的附件标识子集；调用方用「数量是否等于请求数量」判定整体是否通过，
     * 不区分具体哪一个无效（不泄露他人附件是否存在）。</p>
     */
    @Select("""
            <script>
            SELECT a.id
            FROM attachment a
            WHERE a.id IN
            <foreach collection="attachmentIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND a.uploaded_by = #{requesterId}
              AND a.created_at &gt;= #{notBefore}
              AND NOT EXISTS (
                  SELECT 1 FROM ticket_attachment ta WHERE ta.attachment_id = a.id
              )
            FOR UPDATE
            </script>
            """)
    List<Long> selectUsableForBinding(
            @Param("attachmentIds") List<Long> attachmentIds,
            @Param("requesterId") long requesterId,
            @Param("notBefore") LocalDateTime notBefore);

    /** 附件绑定的工单 id（一份附件至多一行绑定）；无绑定返回 null。 */
    @Select("""
            SELECT t.id
            FROM ticket_attachment ta
            JOIN ticket_record r ON r.id = ta.record_id
            JOIN ticket t ON t.id = r.ticket_id
            WHERE ta.attachment_id = #{attachmentId}
            """)
    Long selectBoundTicketId(@Param("attachmentId") long attachmentId);

    /** 附件是否有绑定（孤儿对账的引用判定，与 {@link #selectBoundTicketId} 同表同键）。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM ticket_attachment WHERE attachment_id = #{attachmentId}
            )
            """)
    boolean existsBinding(@Param("attachmentId") long attachmentId);

    /**
     * 工单详情用的附件摘要：绑定到本单（任一记录）的通用附件 + 上传人显示名。
     *
     * <p>固定排序（创建时间升序、同秒按主键升序）保证详情页两次打开的顺序一致。
     * 可见性不再单独判定——能看到工单详情即能看到本单附件；下载与预览仍由附件端点独立授权。</p>
     */
    @Select("""
            SELECT a.id AS id,
                   a.original_name AS originalName,
                   a.content_type AS contentType,
                   a.size_bytes AS sizeBytes,
                   a.uploaded_by AS uploadedById,
                   uploader.display_name AS uploadedByName,
                   a.created_at AS createdAt
            FROM ticket_attachment ta
            JOIN ticket_record r ON r.id = ta.record_id
            JOIN attachment a ON a.id = ta.attachment_id
            JOIN iam_user uploader ON uploader.id = a.uploaded_by
            WHERE r.ticket_id = #{ticketId}
            ORDER BY a.created_at ASC, a.id ASC
            """)
    List<TicketAttachmentRow> selectAttachmentsByTicketId(@Param("ticketId") long ticketId);
}