package com.flowdesk.attachment.application.service;

import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;

/** 通用附件用例接口：独立上传与下载（{@code docs/api-design.md} 7.1/7.2）。 */
public interface AttachmentService {

    /**
     * 独立上传单个附件：校验 → 暂存 → 写元数据 → 原子移动 → 提交；
     * 任一失败不留已提交元数据或孤立文件。
     */
    AttachmentUploadResult upload(UploadAttachmentCommand command);

    /**
     * 下载附件：上传者本人或经业务授权端口可见；不可见与不存在统一抛
     * {@link com.flowdesk.attachment.domain.AttachmentNotFoundException}。
     */
    AttachmentDownloadResult download(long attachmentId);
}