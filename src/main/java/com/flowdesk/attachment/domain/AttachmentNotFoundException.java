package com.flowdesk.attachment.domain;

/**
 * 附件不存在、归属不符或不可见时统一使用的异常
 * （{@code docs/api-design.md} 7.2：三种情况对外都是 {@code 404/ATTACHMENT_NOT_FOUND}，
 * 不泄露附件是否存在）；到 HTTP 的映射在 Web 层完成。
 */
public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException() {
        super("附件不存在");
    }
}