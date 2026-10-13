package com.flowdesk.attachment.application.command;

import org.springframework.web.multipart.MultipartFile;

/** 独立上传入参：单文件（{@code multipart/form-data} 的单个 {@code file} part）。 */
public record UploadAttachmentCommand(MultipartFile file) {
}