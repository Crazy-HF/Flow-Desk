package com.flowdesk.attachment.application.command;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 独立上传入参：{@code multipart/form-data} 的重复 {@code files} part，1～5 个。
 *
 * <p>单文件上传即只带一个 part；批量上传带多个。数量与大小上限来自
 * {@code flowdesk.attachment.max-*} 配置（{@code docs/api-design.md} 7.1/7.6）。</p>
 */
public record UploadAttachmentCommand(List<MultipartFile> files) {

    public UploadAttachmentCommand {
        files = files == null ? List.of() : List.copyOf(files);
    }
}