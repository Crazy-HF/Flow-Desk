package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单附件查询的扁平投影：绑定到本单的通用附件元数据 + 上传人显示名。
 *
 * <p>只取展示与鉴权需要的列；文件内容与存储标识不进入投影——下载/预览一律走公共附件端点，
 * 由后端完成授权后代理输出。</p>
 */
@Data
public class TicketAttachmentRow {
    private Long id;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private Long uploadedById;
    private String uploadedByName;
    private LocalDateTime createdAt;
}
