package com.flowdesk.shared.api;

import java.util.List;

/** 面向调用方的安全错误细节；绝不放入异常栈、SQL 或敏感输入。 */
public record ErrorDetails(String traceId, List<FieldError> fieldErrors) {

    public ErrorDetails {
        fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    public static ErrorDetails trace(String traceId) {
        return new ErrorDetails(traceId, null);
    }
}
