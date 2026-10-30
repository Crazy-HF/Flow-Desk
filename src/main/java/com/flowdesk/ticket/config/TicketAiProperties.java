package com.flowdesk.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 功能开关、受限检索开关与单用户每日调用上限；默认关闭。 */
@ConfigurationProperties(prefix = "flowdesk.ai")
public record TicketAiProperties(Boolean enabled, Integer dailyLimit,
                                 Boolean retrievalEnabled) {

    /** 应用默认关闭、每日上限默认 10 次，并拒绝超出 1～10 的配置。 */
    public TicketAiProperties {
        enabled = enabled == null ? false : enabled;
        dailyLimit = dailyLimit == null ? 10 : dailyLimit;
        // A1 质量门禁未通过；新请求默认只走原五项输入的 v4 路径。
        retrievalEnabled = retrievalEnabled == null ? false : retrievalEnabled;

        if (dailyLimit < 1 || dailyLimit > 10) {
            throw new IllegalStateException(
                    "flowdesk.ai.daily-limit 必须在 1～10 之间");
        }
    }
}
