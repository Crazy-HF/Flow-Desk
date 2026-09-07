package com.flowdesk.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiProfileTest {

    @Test
    void openApiBeanExistsOnlyInLocalAndDemoProfiles() {
        assertThat(hasOpenApi("local")).isTrue();
        assertThat(hasOpenApi("demo")).isTrue();
        assertThat(hasOpenApi("prod")).isFalse();
    }

    private boolean hasOpenApi(String profile) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.register(OpenApiConfiguration.class);
            context.refresh();
            return context.getBeansOfType(OpenAPI.class).containsKey("flowDeskOpenApi");
        }
    }
}
