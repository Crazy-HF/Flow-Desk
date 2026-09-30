package com.flowdesk.common.exception;

import org.springframework.http.HttpStatus;

/** 业务模块通过此异常声明已确认的 HTTP 状态和稳定错误编码。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long resourceVersion;
    private final String resourceStatus;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    /** 冲突时可附带安全的当前快照，供客户端刷新后决定下一步。 */
    public ApiException(HttpStatus status, String code, String message,
            Long resourceVersion, String resourceStatus) {
        super(message);
        this.status = status;
        this.code = code;
        this.resourceVersion = resourceVersion;
        this.resourceStatus = resourceStatus;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long resourceVersion() {
        return resourceVersion;
    }

    public String resourceStatus() {
        return resourceStatus;
    }
}
