package com.flowdesk.ticket.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.support.MockedPersistenceConfiguration;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.result.TicketAiInputSnapshotResult;
import com.flowdesk.ticket.application.result.TicketAiSuggestionResult;
import com.flowdesk.ticket.application.result.TicketAiSourcesPreviewResult;
import com.flowdesk.ticket.application.service.TicketAiSuggestionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 来源预览与生成的 HTTP 契约；权限关系和额度由真实 MySQL 服务测试覆盖。 */
@ActiveProfiles("test")
@SpringBootTest(classes = FlowDeskApplication.class, properties =
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(MockedPersistenceConfiguration.class)
class TicketAiSuggestionControllerWebTest {

    private static final String URL = "/fd/v1/tickets/FD-20261029-001/ai-suggestions";
    private static final String DIGEST = "a".repeat(64);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TicketAiSuggestionService service;
    @MockitoBean private AuthSessionRepository authSessionRepository;

    @Test
    void previewBindsVersionAndReturnsOnlyExternalSourceFields() throws Exception {
        when(service.previewSources("FD-20261029-001", 3L)).thenReturn(
                new TicketAiSourcesPreviewResult(List.of(
                        new TicketAiSourcesPreviewResult.Source(
                                "[S1]", "虚构来源", "检查连接线")), DIGEST));

        mockMvc.perform(get(URL + "/sources-preview").with(user("it"))
                        .queryParam("version", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceDigest").value(DIGEST))
                .andExpect(jsonPath("$.data.sources[0].label").value("[S1]"))
                .andExpect(jsonPath("$.data.sources[0].processExcerpt").value("检查连接线"))
                .andExpect(jsonPath("$.data.sources[0].ticketNo").doesNotExist());

        verify(service).previewSources("FD-20261029-001", 3L);
    }

    @Test
    void generateBindsSourceDigestAndReturnsAuthorizedSnapshot() throws Exception {
        when(service.generate(eq("FD-20261029-001"), any())).thenReturn(
                new TicketAiSuggestionResult(1L, "FD-20261029-001",
                        "11111111-2222-4333-8444-555555555555",
                        new TicketAiInputSnapshotResult("标题", "描述", "办公设备",
                                "MEDIUM", "PROCESSING"), "待核实", List.of("问题"),
                        List.of("步骤 [S1]"), "deepseek-flash", "v5", 1, 1,
                        OffsetDateTime.parse("2026-10-29T12:00:00Z"),
                        List.of(new TicketAiSuggestionResult.Source("[S1]",
                                "FD-20261020-001", "虚构来源", "检查连接线"))));

        mockMvc.perform(post(URL).with(user("it"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"11111111-2222-4333-8444-555555555555",
                                 "version":3,"title":"标题","description":"描述",
                                 "sourceDigest":"%s"}
                                """.formatted(DIGEST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].ticketNo").value("FD-20261020-001"));

        ArgumentCaptor<GenerateTicketAiSuggestionCommand> command =
                ArgumentCaptor.forClass(GenerateTicketAiSuggestionCommand.class);
        verify(service).generate(eq("FD-20261029-001"), command.capture());
        assertThat(command.getValue().sourceDigest()).isEqualTo(DIGEST);
        assertThat(command.getValue().version()).isEqualTo(3L);
    }

    @Test
    void invalidDigestIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post(URL).with(user("it"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"11111111-2222-4333-8444-555555555555",
                                 "version":3,"title":"标题","description":"描述",
                                 "sourceDigest":"invalid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void anonymousPreviewDoesNotReachService() throws Exception {
        mockMvc.perform(get(URL + "/sources-preview").queryParam("version", "3"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
