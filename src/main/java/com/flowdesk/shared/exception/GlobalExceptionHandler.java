package com.flowdesk.shared.exception;

import com.flowdesk.shared.web.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/** 统一将 Web 与业务异常映射为不泄露内部信息的 R 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiErrorWriter errorWriter;

    public GlobalExceptionHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<R<ErrorDetails>> handleValidation(MethodArgumentNotValidException exception) {
        List<com.flowdesk.shared.exception.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        ErrorDetails details = new ErrorDetails(errorWriter.body("VALIDATION_FAILED", "请求字段校验失败")
                .data().traceId(), fieldErrors);
        return ResponseEntity.badRequest().body(R.failure("VALIDATION_FAILED", "请求字段校验失败", details));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<ErrorDetails>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(errorWriter.body(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<R<ErrorDetails>> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorWriter.body("RESOURCE_NOT_FOUND", "请求的资源不存在"));
    }

    /** 方法级授权失败发生在 MVC 调用期间，需与过滤器链中的 403 保持同一契约。 */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<R<ErrorDetails>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorWriter.body("ACCESS_DENIED", "当前身份无权执行该操作"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<R<ErrorDetails>> handleUnexpected(Exception exception) {
        LOG.error("unexpected request failure type={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorWriter.body("INTERNAL_ERROR", "系统暂时无法处理该请求"));
    }

    private com.flowdesk.shared.exception.FieldError fieldError(FieldError error) {
        return new com.flowdesk.shared.exception.FieldError(error.getField(), error.getCode());
    }
}
