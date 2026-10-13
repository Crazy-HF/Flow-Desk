package com.flowdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlowDeskApplication {

    public static void main(String[] args) {

        SpringApplication.run(FlowDeskApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  FlowDesk 启动成功   ლ(´ڡ`ლ)ﾞ");
    }
}
