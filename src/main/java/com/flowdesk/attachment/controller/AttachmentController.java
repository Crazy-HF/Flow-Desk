package com.flowdesk.attachment.controller;

import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;
import com.flowdesk.attachment.application.service.AttachmentService;
import com.flowdesk.common.web.R;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 通用附件接口（2026-10-13 用户确认公共路径约定 {@code attachments/xx}）：
 * 独立上传、下载与内联预览不依赖任何业务模块；可见性由业务授权端口扩展。
 *
 * <p>权限只要求已认证（基础安全链 {@code anyRequest().authenticated()}），不设权限码——
 * 契约未给附件单独定义权限编码（{@code docs/api-design.md} 第 9 节）。</p>
 *
 * <p>响应头是预览安全的一部分：{@code Content-Type} 一律取服务端识别结果，绝不回显客户端声明；
 * {@code X-Content-Type-Options: nosniff} 阻止浏览器把内容改判为 HTML 执行。</p>
 */
@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    /** 内容响应的统一安全头。 */
    private static final String NOSNIFF = "nosniff";
    private static final String CSP_NO_ACTIVE_CONTENT = "default-src 'none'; sandbox";
    private static final String CORP_SAME_ORIGIN = "same-origin";
    private static final String CACHE_PRIVATE = "private, no-store";

    /** PDF 内联时**不**下发 CSP：Chrome 的 PDF 阅读器（PDFium）在沙箱化上下文里会被屏蔽，导致预览直接失败。 */
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 独立上传：重复的 {@code files} part，1～5 个（一个 part 即单文件上传）。
     *
     * <p>整体成功或整体失败（{@code docs/api-design.md} 7.1）：任一文件校验或保存失败，
     * 整个请求失败且不留已提交元数据或孤立文件；错误响应带具体文件名，便于前端逐条提示。
     * 需要逐文件进度与独立取消时，前端按文件逐个调用本端点。</p>
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<List<AttachmentUploadResult>>> upload(
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(attachmentService.upload(new UploadAttachmentCommand(files))));
    }

    /**
     * 内联预览：{@code ?disposition=inline} 请求内联，实际是否内联由服务端按白名单裁决
     * （JPEG / PNG / PDF / TXT 内联，DOCX / XLSX 降级为下载）。
     *
     * <p>缺省 {@code attachment}，等价于下载。</p>
     */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> content(@PathVariable long attachmentId,
            @RequestParam(name = "disposition", defaultValue = "attachment") String disposition) {
        AttachmentDownloadResult result = "inline".equalsIgnoreCase(disposition.trim())
                ? attachmentService.preview(attachmentId)
                : attachmentService.download(attachmentId);
        return fileResponse(result);
    }

    /**
     * 通用下载：原始文件流（不使用 R 信封）；不可见与不存在统一 404。
     *
     * @deprecated 保留为兼容别名，新调用方请使用 {@code GET /attachments/{id}/content}。
     */
    @Deprecated
    @GetMapping("/download/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable long attachmentId) {
        return fileResponse(attachmentService.download(attachmentId));
    }

    private static ResponseEntity<Resource> fileResponse(AttachmentDownloadResult result) {
        ContentDisposition disposition = (result.inline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(result.originalName(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", NOSNIFF)
                .header("Cross-Origin-Resource-Policy", CORP_SAME_ORIGIN)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_PRIVATE);
        if (result.inline() && !PDF_CONTENT_TYPE.equalsIgnoreCase(result.contentType())) {
            builder.header("Content-Security-Policy", CSP_NO_ACTIVE_CONTENT);
        }
        return builder.body(new InputStreamResource(result.content()));
    }
}
