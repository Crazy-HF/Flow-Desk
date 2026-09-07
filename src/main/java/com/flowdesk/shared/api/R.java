package com.flowdesk.shared.api;

/**
 * FlowDesk 普通 JSON 接口的统一响应信封。
 * 文件流和 204 响应不使用此类型。
 */
public record R<T>(String code, String message, T data) {

    public static <T> R<T> success(T data) {
        return new R<>("SUCCESS", "操作成功", data);
    }

    public static <T> R<T> failure(String code, String message, T data) {
        return new R<>(code, message, data);
    }
}
