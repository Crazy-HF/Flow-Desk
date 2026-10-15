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
 * <p>每个类型同时持有规范媒体类型、允许的扩展名与**是否允许内联预览**，供
 * 「扩展名 + 声明媒体类型 + 服务端识别结果」三重一致性校验与预览端点裁决使用；
 * {@link #UNKNOWN} 只作嗅探的内部表达，校验入口不会返回给调用方。</p>
 *
 * <p>内联白名单只有 JPEG、PNG、PDF、TXT 四类**非活动内容**：它们即使被浏览器当作文档打开也无法执行脚本。
 * DOCX / XLSX 一律降级为下载。HTML、SVG 等可执行/可内嵌脚本的类型**根本不在上传白名单里**，
 * 因此不存在「存进来再想办法不内联」的问题——这是预览安全的第一道也是最主要的一道防线。</p>
 */
public enum DetectedFileType {

    JPEG("image/jpeg", Set.of("jpg", "jpeg"), true),
    PNG("image/png", Set.of("png"), true),
    PDF("application/pdf", Set.of("pdf"), true),
    TXT("text/plain", Set.of("txt"), true),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx"), false),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx"), false),
    /** 嗅探未识别：内容不在白名单或容器已损坏，只作内部表达。 */
    UNKNOWN("", Set.of(), false);

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
    private final boolean inlinePreviewable;

    DetectedFileType(String contentType, Set<String> extensions, boolean inlinePreviewable) {
        this.contentType = contentType;
        this.extensions = extensions;
        this.inlinePreviewable = inlinePreviewable;
    }

    /** 服务端识别后写入 {@code attachment.content_type} 的规范媒体类型。 */
    public String contentType() {
        return contentType;
    }

    /**
     * 是否允许内联预览（{@code Content-Disposition: inline}）。
     *
     * <p>只有 JPEG、PNG、PDF、TXT 为 {@code true}；其余类型（含 DOCX / XLSX）一律降级为下载。</p>
     */
    public boolean inlinePreviewable() {
        return inlinePreviewable;
    }

    /**
     * 按已入库的规范媒体类型反查白名单类型；未识别返回 {@code null}。
     *
     * <p>预览端点用它在**不信任客户端**的前提下裁决能否内联：类型来自数据库里服务端识别出的结果。</p>
     */
    public static DetectedFileType fromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return BY_CONTENT_TYPE.get(normalized);
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