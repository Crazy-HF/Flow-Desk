package com.flowdesk.attachment.controller;

import com.flowdesk.attachment.domain.AttachmentNotFoundException;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.common.exception.ApiErrorWriter;
import com.flowdesk.common.exception.ErrorDetails;
import com.flowdesk.common.web.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 附件模块的异常映射（{@code docs/api-design.md} 10.2）。
 *
 * <p>必须带 {@link Ordered#HIGHEST_PRECEDENCE}：{@code common} 的
 * {@code GlobalExceptionHandler} 有末位 {@code @ExceptionHandler(Exception.class)} 兜底，
 * 若本 Advice 排在它之后，附件的 400/413/415/404 全部会被吞成 500。
 * 映射放在模块内而不是 {@code common}，保持 {@code common} 不反向依赖附件模块。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AttachmentExceptionHandler {

    private final ApiErrorWriter errorWriter;

    public AttachmentExceptionHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /** 校验失败按 Violation 映射：大小/数量 → 413，类型 → 415，其余 → 400。 */
    @ExceptionHandler(AttachmentValidationException.class)
    ResponseEntity<R<ErrorDetails>> handleValidation(AttachmentValidationException exception) {
        return switch (exception.violation()) {
            case FILE_TOO_LARGE, TOTAL_TOO_LARGE, TOO_MANY_FILES -> failure(
                    HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", exception.getMessage());
            case UNSUPPORTED_TYPE -> failure(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_TYPE_UNSUPPORTED", exception.getMessage());
            case EMPTY_FILE, INVALID_NAME -> failure(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage());
        };
    }

    /** multipart 解析在进入控制器之前就超限：同样按 413 契约返回，而不是落成 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<R<ErrorDetails>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return failure(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "单文件、数量或总大小超过限制");
    }

    /** 附件不存在、归属不符或不可见统一 404，不泄露附件是否存在。 */
    @ExceptionHandler(AttachmentNotFoundException.class)
    ResponseEntity<R<ErrorDetails>> handleNotFound(AttachmentNotFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在");
    }

    private ResponseEntity<R<ErrorDetails>> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorWriter.body(code, message));
    }
}