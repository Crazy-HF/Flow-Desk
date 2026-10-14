package com.flowdesk.attachment.infrastructure;

/**
 * 附件存储层失败。
 *
 * <p>消息里携带失败对象（storageKey 或探针路径），供补偿逻辑与对账定位；
 * 如何回滚、如何映射为对外错误码由业务编排层决定，本层不自带 HTTP 语义。</p>
 */
public class AttachmentStorageException extends RuntimeException {

    public AttachmentStorageException(String message) {
        super(message);
    }

    public AttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}