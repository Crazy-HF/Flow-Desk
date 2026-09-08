package com.flowdesk.shared.web;

/**
 * FlowDesk 普通 JSON 接口的统一响应信封。
 * 文件流和 204 响应不使用此类型；HTTP 状态由响应本身表达，code 表示稳定业务码。
 */
public record R<T>(String code, String message, T data) {

    public static final String OK = "OK";

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> success(T data) {
        return new R<>(OK, "操作成功", data);
    }

    public static <T> R<T> failure(String code, String message) {
        return failure(code, message, null);
    }

    public static <T> R<T> failure(String code, String message, T data) {
        return new R<>(code, message, data);
    }
}
