package com.flowdesk.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 所有业务时间从同一个 UTC 时钟注入，便于测试且避免服务器时区漂移。 */
@Configuration
public class TimeConfiguration {

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
