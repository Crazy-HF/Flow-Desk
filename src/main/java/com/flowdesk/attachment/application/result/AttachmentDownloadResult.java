package com.flowdesk.attachment.application.result;

import java.io.InputStream;

/**
 * 下载结果：元数据 + 已打开的最终文件流。
 *
 * <p>流的生命周期归调用方（Web 层写完响应后关闭）；服务层不缓存流。</p>
 */
public record AttachmentDownloadResult(
        long id,
        String originalName,
        String contentType,
        long sizeBytes,
        InputStream content) {
}