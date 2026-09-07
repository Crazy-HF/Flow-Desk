package com.flowdesk.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** OpenAPI 仅在 local/demo Profile 暴露；prod 由配置和此 Profile 双重关闭。 */
@Configuration
@Profile({"local", "demo"})
public class OpenApiConfiguration {

    /**
     * 配置 OpenAPI
     * @return
     */
    @Bean
    OpenAPI flowDeskOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FlowDesk API").version("v1").description("企业工单协作平台接口"))
                .schemaRequirement("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
