package com.flowdesk.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 通用附件模块配置（{@code flowdesk.attachment.*}）。
 *
 * <p>{@code root} 必须提供：附件受控目录位于 Web 根目录之外，使用绝对路径并持久化
 * （部署清单见 {@code docs/engineering-readiness.md} 第 5 节）。暂存目录与最终目录固定为
 * root 下的 staging / files 子目录，由存储组件启动时校验并自动创建，不单独暴露配置——
 * 它们必须与根目录同卷，拆开配置只会制造错误空间。</p>
 *
 * <p>{@code retention} 是暂存文件与「上传后未绑定」附件的保留期（与 {@code docs/api-design.md} 7.1
 * 的默认 24 小时一致）：对账任务按它清理，工单侧的引用校验也按它判断附件是否可引用——
 * 两者必须同一口径，否则会出现「刚引用完就被清理」。缺失时取默认值，
 * 与 {@code TicketProperties} 的默认值策略一致。</p>
 *
 * <p>本类自 2026-10-12（方案 B）起属于独立于工单的通用附件模块
 * （{@code com.flowdesk.attachment}），不依赖任何业务模块。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.attachment")
public record AttachmentProperties(Path root, Duration retention) {

    /** 保留期默认值：{@code docs/api-design.md} 7.1 已确认的 24 小时。 */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    private static final Duration MIN_RETENTION = Duration.ofMinutes(1);

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
    }
}