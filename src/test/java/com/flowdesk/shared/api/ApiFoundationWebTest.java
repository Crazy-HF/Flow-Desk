package com.flowdesk.shared.api;

import com.flowdesk.FlowDeskApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 仅注册测试探针来验证公共 Web 契约；这些路径不会进入生产应用组件扫描。
 */
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(ApiFoundationWebTest.ProbeConfiguration.class)
class ApiFoundationWebTest {

    private static final String TRACE_ID = "test-trace-id-20260907";

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestUses401EnvelopeWithTraceId() throws Exception {
        mockMvc.perform(get("/fd/v1/test-probe/protected").header(TraceIdFilter.HEADER_NAME, TRACE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceIdFilter.HEADER_NAME, TRACE_ID))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.data.traceId").value(TRACE_ID));
    }

    @Test
    @WithMockUser
    void validationFailureUses400EnvelopeWithoutRequestValue() throws Exception {
        mockMvc.perform(post("/fd/v1/test-probe/validation")
                        .header(TraceIdFilter.HEADER_NAME, TRACE_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.data.fieldErrors[0].code").value("NotBlank"));
    }

    @Test
    @WithMockUser
    void notFoundAndConflictUseTheirDocumentedShapes() throws Exception {
        mockMvc.perform(get("/fd/v1/test-probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());

        mockMvc.perform(get("/fd/v1/test-probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    @Test
    @WithMockUser
    void authenticatedRequestWithoutRequiredAuthorityUses403Envelope() throws Exception {
        mockMvc.perform(get("/fd/v1/test-probe/requires-authority"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    static class ProbeConfiguration {

        @RestController
        @RequestMapping("/fd/v1/test-probe")
        static class ProbeController {

            @PostMapping("/validation")
            R<Void> validation(@Valid @RequestBody ProbeRequest request) {
                return R.success(null);
            }

            @RequestMapping("/not-found")
            R<Void> notFound() {
                throw new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在或不可见");
            }

            @RequestMapping("/conflict")
            R<Void> conflict() {
                throw new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT", "工单状态已变化");
            }

            @RequestMapping("/requires-authority")
            @PreAuthorize("hasAuthority('FOUNDATION_ADMIN')")
            R<Void> requiresAuthority() {
                return R.success(null);
            }

            @RequestMapping("/protected")
            R<Void> protectedEndpoint() {
                return R.success(null);
            }
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
