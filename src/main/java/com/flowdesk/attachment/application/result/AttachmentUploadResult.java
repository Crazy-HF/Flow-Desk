package com.flowdesk.attachment.application.result;

import java.time.LocalDateTime;

/**
 * 上传成功结果：附件标识与元数据。
 *
 * <p>不返回内部存储标识（{@code storageKey}）——下载与后续引用一律使用附件标识。</p>
 */
public record AttachmentUploadResult(
        long id,
        String originalName,
        String contentType,
        long sizeBytes,
        LocalDateTime createdAt) {
}