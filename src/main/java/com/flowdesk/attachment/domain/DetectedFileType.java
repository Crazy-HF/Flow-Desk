package com.flowdesk.attachment.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 附件白名单类型（{@code docs/api-design.md} 7.1/7.6）：JPEG、PNG、PDF、TXT、DOCX、XLSX。
 *
 * <p>每个类型同时持有规范媒体类型与允许的扩展名，供「扩展名 + 声明媒体类型 + 服务端识别结果」
 * 三重一致性校验使用；{@link #UNKNOWN} 只作嗅探的内部表达，校验入口不会返回给调用方。</p>
 */
public enum DetectedFileType {

    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    PNG("image/png", Set.of("png")),
    PDF("application/pdf", Set.of("pdf")),
    TXT("text/plain", Set.of("txt")),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx")),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx")),
    /** 嗅探未识别：内容不在白名单或容器已损坏，只作内部表达。 */
    UNKNOWN("", Set.of());

    private static final Map<String, DetectedFileType> BY_CONTENT_TYPE = Stream.of(values())
            .filter(type -> !type.contentType.isEmpty())
            .collect(Collectors.toUnmodifiableMap(type -> type.contentType, Function.identity()));

    private static final Map<String, DetectedFileType> BY_EXTENSION = Stream.of(values())
            .flatMap(type -> type.extensions.stream().map(extension -> Map.entry(extension, type)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /** 非标准但客户端常见的 JPEG 声明别名。 */
    private static final String JPEG_CONTENT_TYPE_ALIAS = "image/jpg";

    private final String contentType;
    private final Set<String> extensions;

    DetectedFileType(String contentType, Set<String> extensions) {
        this.contentType = contentType;
        this.extensions = extensions;
    }

    /** 服务端识别后写入 {@code attachment.content_type} 的规范媒体类型。 */
    public String contentType() {
        return contentType;
    }

    /** 按文件名的扩展名解析白名单类型（忽略大小写）；无法识别返回 {@code null}。 */
    public static DetectedFileType fromFileName(String originalName) {
        if (originalName == null) {
            return null;
        }
        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || dot == originalName.length() - 1) {
            return null;
        }
        return BY_EXTENSION.get(originalName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 按声明的媒体类型解析白名单类型：忽略大小写与参数（如 {@code ; charset=UTF-8}）；
     * 无法识别（含 {@code application/octet-stream} 这类通用类型）返回 {@code null}。
     */
    public static DetectedFileType fromDeclaredContentType(String declaredContentType) {
        if (declaredContentType == null) {
            return null;
        }
        String normalized = declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (JPEG_CONTENT_TYPE_ALIAS.equals(normalized)) {
            return JPEG;
        }
        return BY_CONTENT_TYPE.get(normalized);
    }
}