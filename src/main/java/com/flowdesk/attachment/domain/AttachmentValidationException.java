package com.flowdesk.attachment.domain;

/**
 * 附件校验失败。携带 {@link Violation} 供 Web 层映射对外错误码
 * （{@code docs/api-design.md} 10.2：`413/ATTACHMENT_TOO_LARGE`、`415/ATTACHMENT_TYPE_UNSUPPORTED`、
 * `400/VALIDATION_FAILED`），本层不自带 HTTP 语义。
 */
public class AttachmentValidationException extends RuntimeException {

    /** 失败类别：决定对外错误码，具体文案由 message 承载。 */
    public enum Violation {
        /** 空文件。 */
        EMPTY_FILE,
        /** 单文件超过 10 MiB（声明大小或暂存后的实际大小）。 */
        FILE_TOO_LARGE,
        /** 单次请求文件总大小超过 25 MiB。 */
        TOTAL_TOO_LARGE,
        /** 单次请求文件数量超限。 */
        TOO_MANY_FILES,
        /** 原始文件名去首尾空白后为空或超长。 */
        INVALID_NAME,
        /** 类型不在白名单，或扩展名、声明媒体类型、服务端识别结果三者不一致。 */
        UNSUPPORTED_TYPE
    }

    private final Violation violation;

    public AttachmentValidationException(Violation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public Violation violation() {
        return violation;
    }
}