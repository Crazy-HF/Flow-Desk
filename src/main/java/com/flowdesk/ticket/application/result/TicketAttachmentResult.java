package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

/**
 * 工单详情里的附件摘要（{@code docs/api-design.md} 5.4 的 {@code attachments[]}）。
 *
 * <p>只给展示与入口所需的信息：谁上传的、多大、什么时候、能不能内联预览。
 * {@code inlinePreviewable} 由后端按内联白名单给出，前端因此不需要复制一份白名单；
 * 真正的内联裁决仍在预览端点再做一次（纵深防御）。</p>
 */
public record TicketAttachmentResult(
        long id,
        String originalName,
        String contentType,
        long sizeBytes,
        TicketUserSummaryResult uploadedBy,
        OffsetDateTime createdAt,
        boolean inlinePreviewable) {
}
