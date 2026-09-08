package com.flowdesk.shared.exception;

import org.springframework.http.HttpStatus;

/** 业务模块通过此异常声明已确认的 HTTP 状态和稳定错误编码。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
