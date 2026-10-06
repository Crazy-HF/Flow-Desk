package com.flowdesk.common.exception;

import com.flowdesk.common.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/** 统一将 Web 与业务异常映射为不泄露内部信息的 R 响应。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ApiErrorWriter errorWriter;

    public GlobalExceptionHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<R<ErrorDetails>> handleValidation(MethodArgumentNotValidException exception) {
        List<com.flowdesk.common.exception.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        ErrorDetails details = new ErrorDetails(errorWriter.body("VALIDATION_FAILED", "请求字段校验失败")
                .data().traceId(), fieldErrors);
        return ResponseEntity.badRequest().body(R.failure("VALIDATION_FAILED", "请求字段校验失败", details));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<ErrorDetails>> handleApiException(ApiException exception) {
        if (exception.resourceVersion() != null
                && exception.resourceStatus() != null) {
            String traceId = errorWriter.body(exception.code(), exception.getMessage())
                    .data().traceId();
            ErrorDetails details = new ErrorDetails(
                    traceId, null,
                    exception.resourceVersion(), exception.resourceStatus());
            return ResponseEntity.status(exception.status())
                    .body(R.failure(exception.code(), exception.getMessage(), details));
        }
        return ResponseEntity.status(exception.status())
                .body(errorWriter.body(exception.code(), exception.getMessage()));
    }

    /** 请求体无法解析（JSON 语法错误或编码不一致）属于调用方输入问题，不能按服务端错误返回。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<R<ErrorDetails>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(errorWriter.body("VALIDATION_FAILED", "请求体格式不正确"));
    }

    /**
     * 查询参数缺失、类型不符，路径变量类型不符（例如把非数字传给 {@code long} 主键），
     * 或 {@code multipart/form-data} 缺少必需的 part（例如创建工单缺 {@code ticket} 部分）。
     *
     * <p>这四类都是调用方把请求写错了，按 10.2 统一错误契约应当返回
     * {@code 400/VALIDATION_FAILED}；不处理的话会落到末位的兜底分支变成
     * {@code 500/INTERNAL_ERROR}，把调用方的问题报成服务端故障。
     * 对外不回显参数名与原始取值，避免把内部字段名泄露出去。</p>
     *
     * <p>{@code MissingServletRequestPartException} 与
     * {@code MissingServletRequestParameterException} 是兄弟类型而非父子，
     * 所以必须单独登记，否则创建工单缺少 {@code ticket} 部分会返回 500
     * （2026-10-06 由 {@code TicketControllerWebTest} 实测暴露，经用户授权修正）。</p>
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<R<ErrorDetails>> handleBadRequestParameter(Exception exception) {
        return ResponseEntity.badRequest()
                .body(errorWriter.body("VALIDATION_FAILED", "请求参数不符合要求"));
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
        // 末位参数传异常对象，保留完整堆栈便于排查；日志消息本身不含请求数据，不会泄露敏感信息
        log.error("unexpected request failure type={}", exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorWriter.body("INTERNAL_ERROR", "系统暂时无法处理该请求"));
    }

    private com.flowdesk.common.exception.FieldError fieldError(FieldError error) {
        return new com.flowdesk.common.exception.FieldError(error.getField(), error.getCode());
    }
}
