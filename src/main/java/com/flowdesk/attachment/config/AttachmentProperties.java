package com.flowdesk.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 通用附件模块配置（{@code flowdesk.attachment.*}）。
 *
 * <p>{@code root} 必须提供：它是**本地暂存根目录**，必须位于 Web 根目录之外、使用绝对路径、可写且持久化
 * （部署清单见 {@code docs/engineering-readiness.md} 第 5 节）。两种驱动都用它承载暂存：
 * {@code local} 驱动在 root 下再建 {@code files} 最终目录（两者必须同卷，启动时校验），
 * {@code s3} 驱动的最终存储在对象存储里，root 下只保留 {@code staging}。
 * 子目录不单独暴露配置——拆开配置只会制造错误空间。</p>
 *
 * <p>{@code retention} 是暂存文件与「上传后未绑定」附件的保留期（与 {@code docs/api-design.md} 7.1
 * 的默认 24 小时一致）：对账任务按它清理，工单侧的引用校验也按它判断附件是否可引用——
 * 两者必须同一口径，否则会出现「刚引用完就被清理」。</p>
 *
 * <p>限额（{@code max-file-size} 等）默认值与 {@code docs/api-design.md} 7.1/7.6 一致，但**可配置**：
 * 调整限额只需改配置，不再改代码。注意 Spring 的 {@code spring.servlet.multipart.*} 是外层闸且为静态配置，
 * 调高这里必须同步调高外层闸，否则请求在进入应用层之前就被拒绝。</p>
 *
 * <p>本类自 2026-10-12（方案 B）起属于独立于工单的通用附件模块
 * （{@code com.flowdesk.attachment}），不依赖任何业务模块。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.attachment")
public record AttachmentProperties(
        Path root,
        Duration retention,
        AttachmentStorageKind storage,
        DataSize maxFileSize,
        Integer maxFilesPerRequest,
        DataSize maxRequestTotalSize,
        Integer maxOriginalNameLength,
        S3 s3) {

    /** 保留期默认值：{@code docs/api-design.md} 7.1 已确认的 24 小时。 */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    private static final Duration MIN_RETENTION = Duration.ofMinutes(1);

    /** 限额默认值：{@code docs/api-design.md} 7.1/7.6 已确认。 */
    private static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(10);
    private static final int DEFAULT_MAX_FILES_PER_REQUEST = 5;
    private static final DataSize DEFAULT_MAX_REQUEST_TOTAL_SIZE = DataSize.ofMegabytes(25);
    private static final int DEFAULT_MAX_ORIGINAL_NAME_LENGTH = 255;

    public AttachmentProperties {
        if (root == null) {
            throw new IllegalStateException(
                    "flowdesk.attachment.root 未配置：请通过 FLOWDESK_ATTACHMENT_ROOT 指定 Web 根目录之外的绝对持久化目录");
        }
        if (!root.isAbsolute()) {
            throw new IllegalStateException("flowdesk.attachment.root 必须是绝对路径，当前值：" + root);
        }

        if (retention == null) {
            retention = DEFAULT_RETENTION;
        }
        if (retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException(
                    "flowdesk.attachment.retention 必须是不小于 1m 的时长，例如 24h");
        }

        if (storage == null) {
            storage = AttachmentStorageKind.LOCAL;
        }

        if (maxFileSize == null) {
            maxFileSize = DEFAULT_MAX_FILE_SIZE;
        }
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalStateException("flowdesk.attachment.max-file-size 必须大于 0");
        }

        if (maxFilesPerRequest == null) {
            maxFilesPerRequest = DEFAULT_MAX_FILES_PER_REQUEST;
        }
        if (maxFilesPerRequest < 1) {
            throw new IllegalStateException("flowdesk.attachment.max-files-per-request 必须不小于 1");
        }

        if (maxRequestTotalSize == null) {
            maxRequestTotalSize = DEFAULT_MAX_REQUEST_TOTAL_SIZE;
        }
        if (maxRequestTotalSize.toBytes() < maxFileSize.toBytes()) {
            throw new IllegalStateException(
                    "flowdesk.attachment.max-request-total-size 不能小于 flowdesk.attachment.max-file-size");
        }

        if (maxOriginalNameLength == null) {
            maxOriginalNameLength = DEFAULT_MAX_ORIGINAL_NAME_LENGTH;
        }
        if (maxOriginalNameLength < 1) {
            throw new IllegalStateException("flowdesk.attachment.max-original-name-length 必须不小于 1");
        }

        if (s3 == null) {
            s3 = S3.EMPTY;
        }
        if (storage == AttachmentStorageKind.S3) {
            s3.requireUsable();
        }
    }

    /**
     * S3 兼容对象存储配置（{@code flowdesk.attachment.s3.*}）。
     *
     * <p>{@code endpoint} 留空表示使用官方 AWS S3；MinIO 与 OSS 必须给出 endpoint。
     * {@code path-style} 默认开启——MinIO 必须使用路径风格寻址，官方 S3 与 OSS 也可用。</p>
     *
     * <p>桶内只认受控形状（32 位小写十六进制）的对象名，其它对象一律忽略，与本地驱动同一口径；
     * 仍建议为附件使用独立桶，避免与其它数据混放。</p>
     */
    public record S3(
            String endpoint,
            String bucket,
            String region,
            String accessKey,
            String secretKey,
            Boolean pathStyle) {

        static final S3 EMPTY = new S3(null, null, null, null, null, null);

        public S3 {
            if (region == null || region.isBlank()) {
                region = "us-east-1";
            }
            if (pathStyle == null) {
                pathStyle = Boolean.TRUE;
            }
            endpoint = blankToNull(endpoint);
            bucket = blankToNull(bucket);
            accessKey = blankToNull(accessKey);
            secretKey = blankToNull(secretKey);
        }

        /** {@code storage=s3} 时启动即校验：缺配置直接让应用启动失败，不留到第一次上传才发现。 */
        void requireUsable() {
            requirePresent(bucket, "flowdesk.attachment.s3.bucket");
            requirePresent(accessKey, "flowdesk.attachment.s3.access-key");
            requirePresent(secretKey, "flowdesk.attachment.s3.secret-key");
        }

        private static void requirePresent(String value, String key) {
            if (value == null) {
                throw new IllegalStateException(
                        "flowdesk.attachment.storage=s3 时必须配置 " + key + "（建议通过环境变量注入，不写入仓库）");
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
