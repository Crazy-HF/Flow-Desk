package com.flowdesk.attachment.application.service;

import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;

import java.util.List;

/** 通用附件用例接口：独立上传、下载与内联预览。 */
public interface AttachmentService {

    /**
     * 独立上传 1～5 个附件：声明预检 → 逐个暂存与校验 → 事务内写全部元数据 →
     * 逐个提交到最终存储 → 提交事务；**整体成功或整体失败**，任一失败不留已提交元数据或孤立文件。
     *
     * @return 与入参顺序一致的逐文件结果
     */
    List<AttachmentUploadResult> upload(UploadAttachmentCommand command);

    /**
     * 下载附件（强制 {@code Content-Disposition: attachment}）：上传者本人或经业务授权端口可见；
     * 不可见与不存在统一抛 {@link com.flowdesk.attachment.domain.AttachmentNotFoundException}。
     */
    AttachmentDownloadResult download(long attachmentId);

    /**
     * 预览附件（请求内联）：是否真的内联由**服务端**按内联白名单裁决——
     * 只有 JPEG / PNG / PDF / TXT 内联，其余类型降级为下载（结果里的 {@code inline} 即裁决结果）。
     *
     * <p>可见性与 {@link #download} 完全一致：预览走的是「后端授权 → 后端代理内容」这条同一条路，
     * 绝不改成签名 URL 重定向——那等于绕过应用授权，URL 一泄露就是公开文件。</p>
     */
    AttachmentDownloadResult preview(long attachmentId);
}