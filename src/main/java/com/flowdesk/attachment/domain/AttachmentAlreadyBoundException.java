package com.flowdesk.attachment.domain;

/**
 * 附件已被业务对象引用、不允许删除时使用的异常
 * （{@code docs/api-design.md} 7.1：只允许删除**尚未绑定**的附件）。
 *
 * <p>已绑定附件是工单审计链的一部分（记录不可变），删除会破坏可追溯性，
 * 因此这里不是「参数错误」而是状态冲突，到 HTTP 的映射为 {@code 409/ATTACHMENT_ALREADY_BOUND}。</p>
 */
public class AttachmentAlreadyBoundException extends RuntimeException {

    public AttachmentAlreadyBoundException() {
        super("附件已绑定业务记录，不能删除");
    }
}
