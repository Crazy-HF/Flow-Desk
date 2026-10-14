package com.flowdesk.attachment.application.port;

/**
 * 附件业务可见性出口：通用附件模块定义，由业务模块实现（工单模块在绑定接入时实现：
 * 附件绑定的工单对调用者可见即返回 {@code true}）。
 *
 * <p>实现可缺省：不存在实现时，除上传者本人外一律不可见（通用模块保持业务无关、拷走即用）。</p>
 */
public interface AttachmentAccessPort {

    /** 附件是否对指定用户可见（上传者之外的业务授权路径）。 */
    boolean isVisibleTo(long attachmentId, long userId);

    /**
     * 附件是否已被任何业务对象引用（孤儿对账用：未绑定附件超过保留期后进入清理流程）。
     *
     * <p>缺少实现（可选 port 未装配）时，对账任务**跳过**未绑定附件清理——
     * 宁可文件堆积，也不在没有绑定判定的部署里误删仍在使用的附件。</p>
     */
    boolean isReferenced(long attachmentId);
}