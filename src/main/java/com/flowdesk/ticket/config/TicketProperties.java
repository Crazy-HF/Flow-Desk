package com.flowdesk.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工单动作配置（{@code flowdesk.ticket.*}）。
 *
 * <p>只暴露"随环境变化"的值。两个期限都属业务规则而不是安全策略，但长度需要能按环境调整
 * 以便验收，因此放在配置里并给出与已确认规则一致的默认值。</p>
 *
 * <p>默认值不是可选项：{@code OpenApiProfileTest} 手工创建 {@code AnnotationConfigApplicationContext}，
 * 缺少绑定值会让没有 yml 来源的上下文启动失败。</p>
 *
 * <p><b>两个期限都没有定时任务</b>：本版本只写入期限并在界面展示，到期不会自动改变工单状态
 * （用户 2026-10-06 裁决，见 {@code docs/implementation-plan.md} 9.3 第 4 条）。
 * 待确认超时自动完成与待补充超时自动关闭按完整版 backlog 第 3 项单独设计。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.ticket")
public record TicketProperties(
        Duration confirmationWindow,
        Duration supplementWindow,
        Duration cancelRequestWindow) {

    /** 员工确认期限：IT 最近一次提交解决结果后 7×24 小时（docs/kickoff.md 已确认规则）。 */
    private static final Duration DEFAULT_CONFIRMATION_WINDOW = Duration.ofDays(7);
    private static final Duration MIN_CONFIRMATION_WINDOW = Duration.ofMinutes(1);

    /** 员工补充期限：IT 最近一次请求补充后 7×24 小时（docs/kickoff.md 4.12 已确认规则）。 */
    private static final Duration DEFAULT_SUPPLEMENT_WINDOW = Duration.ofDays(7);
    private static final Duration MIN_SUPPLEMENT_WINDOW = Duration.ofMinutes(1);

    /**
     * 撤销请求的响应期限：提交人发起后 IT 需在此期限内批准或拒绝（docs/kickoff.md 4.7 未来方向）。
     *
     * <p>默认取 3 天而不是与上面两个一致的 7 天：这个窗口约束的是 IT 侧的响应，
     * 与"工单已被领取却被挂起"的容忍度不同。它是配置项，改 {@code cancel-request-window} 即可。</p>
     */
    private static final Duration DEFAULT_CANCEL_REQUEST_WINDOW = Duration.ofDays(3);
    private static final Duration MIN_CANCEL_REQUEST_WINDOW = Duration.ofMinutes(1);

    public TicketProperties {
        if (confirmationWindow == null) {
            confirmationWindow = DEFAULT_CONFIRMATION_WINDOW;
        }
        if (confirmationWindow.compareTo(MIN_CONFIRMATION_WINDOW) < 0) {
            throw new IllegalStateException(
                    "flowdesk.ticket.confirmation-window 必须是不小于 1m 的时长，例如 7d");
        }

        if (supplementWindow == null) {
            supplementWindow = DEFAULT_SUPPLEMENT_WINDOW;
        }
        if (supplementWindow.compareTo(MIN_SUPPLEMENT_WINDOW) < 0) {
            throw new IllegalStateException(
                    "flowdesk.ticket.supplement-window 必须是不小于 1m 的时长，例如 7d");
        }

        if (cancelRequestWindow == null) {
            cancelRequestWindow = DEFAULT_CANCEL_REQUEST_WINDOW;
        }
        if (cancelRequestWindow.compareTo(MIN_CANCEL_REQUEST_WINDOW) < 0) {
            throw new IllegalStateException(
                    "flowdesk.ticket.cancel-request-window 必须是不小于 1m 的时长，例如 3d");
        }
    }
}
