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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 通用附件接口（2026-10-13 用户确认公共路径约定 {@code attachments/xx}）：
 * 独立上传与下载不依赖任何业务模块；下载可见性由业务授权端口扩展。
 *
 * <p>权限只要求已认证（基础安全链 {@code anyRequest().authenticated()}），不设权限码——
 * 契约未给附件单独定义权限编码（{@code docs/api-design.md} 第 9 节）。</p>
 */
@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /** 独立上传：单文件 {@code file} part；成功返回附件标识与元数据。 */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<AttachmentUploadResult>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(attachmentService.upload(new UploadAttachmentCommand(file))));
    }

    /** 通用下载：原始文件流（不使用 R 信封）；不可见与不存在统一 404。 */
    @GetMapping("/download/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable long attachmentId) {
        AttachmentDownloadResult download = attachmentService.download(attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.originalName(), StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.content()));
    }
}