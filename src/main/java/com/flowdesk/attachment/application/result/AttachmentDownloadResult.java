package com.flowdesk.attachment.application.result;

import java.io.InputStream;

/**
 * 下载/预览结果：元数据 + 已打开的最终文件流 + 实际生效的处置方式。
 *
 * <p>{@code inline} 是**服务端裁决后**的结果，不是客户端的请求值：客户端可以请求 {@code disposition=inline}，
 * 但只有内联白名单（JPEG / PNG / PDF / TXT）才会真的内联，其余类型一律降级为 {@code attachment}（下载）。</p>
 *
 * <p>流的生命周期归调用方（Web 层写完响应后关闭）；服务层不缓存流。</p>
 */
public record AttachmentDownloadResult(
        long id,
        String originalName,
        String contentType,
        long sizeBytes,
        boolean inline,
        InputStream content) {
}
