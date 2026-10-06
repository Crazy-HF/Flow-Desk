package com.flowdesk.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工单动作配置（{@code flowdesk.ticket.*}）。
 *
 * <p>只暴露"随环境变化"的值。确认期限属于业务规则而不是安全策略，但它的长度需要能按环境调整
 * 以便验收，因此放在配置里并给出与已确认规则一致的默认值。</p>
 *
 * <p>默认值不是可选项：{@code OpenApiProfileTest} 手工创建 {@code AnnotationConfigApplicationContext}，
 * 缺少绑定值会让没有 yml 来源的上下文启动失败。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.ticket")
public record TicketProperties(Duration confirmationWindow) {

    /** 员工确认期限：IT 最近一次提交解决结果后 7×24 小时（docs/kickoff.md 已确认规则）。 */
    private static final Duration DEFAULT_CONFIRMATION_WINDOW = Duration.ofDays(7);
    private static final Duration MIN_CONFIRMATION_WINDOW = Duration.ofMinutes(1);

    public TicketProperties {
        if (confirmationWindow == null) {
            confirmationWindow = DEFAULT_CONFIRMATION_WINDOW;
        }
        if (confirmationWindow.compareTo(MIN_CONFIRMATION_WINDOW) < 0) {
            throw new IllegalStateException(
                    "flowdesk.ticket.confirmation-window 必须是不小于 1m 的时长，例如 7d");
        }
    }
}
