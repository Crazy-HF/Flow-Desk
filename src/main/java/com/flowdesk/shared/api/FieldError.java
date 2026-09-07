package com.flowdesk.shared.api;

/** 字段校验错误只暴露字段名和稳定规则编码，不回显原始输入。 */
public record FieldError(String field, String code) {
}
