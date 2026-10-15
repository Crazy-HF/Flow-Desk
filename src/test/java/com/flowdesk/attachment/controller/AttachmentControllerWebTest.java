package com.flowdesk.attachment.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;
import com.flowdesk.attachment.application.service.AttachmentService;
import com.flowdesk.attachment.domain.AttachmentAlreadyBoundException;
import com.flowdesk.attachment.domain.AttachmentNotFoundException;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.attachment.domain.AttachmentValidationException.Violation;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.support.MockedPersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件接口的 Web 契约测试（{@code docs/api-design.md} 7.1/7.2）。
 *
 * <p>重点是**预览安全响应头**：内联只对白名单类型生效、{@code Content-Type} 取服务端识别结果、
 * {@code nosniff} 与 CSP 的存在性（PDF 例外，见 {@link AttachmentController} 的说明）。
 * 这些是"看得见的防线"，必须在 HTTP 层真的断言，不能只靠服务层返回值。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(MockedPersistenceConfiguration.class)
class AttachmentControllerWebTest {

    private static final String CONTENT = "/attachments/1/content";
    private static final String DOWNLOAD = "/attachments/download/1";
    private static final String UPLOAD = "/attachments/upload";
    private static final String DELETE = "/attachments/1";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
    private static final byte[] PDF = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @Test
    void inlineImageCarriesInlineDispositionAndFullSecurityHeaders() throws Exception {
        when(attachmentService.preview(1L)).thenReturn(download("shot.png", "image/png", PNG, true));

        mockMvc.perform(get(CONTENT).param("disposition", "inline").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    @Test
    void inlinePdfSkipsContentSecurityPolicyBecauseItWouldBlockTheViewer() throws Exception {
        when(attachmentService.preview(1L)).thenReturn(download("spec.pdf", "application/pdf", PDF, true));

        mockMvc.perform(get(CONTENT).param("disposition", "inline").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    void officeDocumentRequestedInlineIsDowngradedToAttachment() throws Exception {
        // 服务端裁决：DOCX 不在内联白名单里，即使客户端请求 inline 也只给下载。
        when(attachmentService.preview(1L)).thenReturn(download("plan.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", PDF, false));

        mockMvc.perform(get(CONTENT).param("disposition", "inline").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")));
    }

    @Test
    void contentWithoutDispositionParameterDefaultsToAttachment() throws Exception {
        when(attachmentService.download(1L)).thenReturn(download("shot.png", "image/png", PNG, false));

        mockMvc.perform(get(CONTENT).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")));
    }

    @Test
    void deprecatedDownloadAliasStillServesTheFile() throws Exception {
        when(attachmentService.download(1L)).thenReturn(download("shot.png", "image/png", PNG, false));

        MvcResult result = mockMvc.perform(get(DOWNLOAD).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PNG);
    }

    @Test
    void invisibleOrMissingAttachmentIsReportedAsNotFound() throws Exception {
        when(attachmentService.preview(1L)).thenThrow(new AttachmentNotFoundException());

        mockMvc.perform(get(CONTENT).param("disposition", "inline").with(authenticatedUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeTouchingTheService() throws Exception {
        mockMvc.perform(get(CONTENT).param("disposition", "inline"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadAcceptsRepeatedFilesPartsAndReturnsPerFileResults() throws Exception {
        when(attachmentService.upload(any(UploadAttachmentCommand.class))).thenReturn(List.of(
                new AttachmentUploadResult(1L, "a.txt", "text/plain", 3L,
                        LocalDateTime.parse("2026-10-14T02:00:00")),
                new AttachmentUploadResult(2L, "b.png", "image/png", 4L,
                        LocalDateTime.parse("2026-10-14T02:00:00"))));

        mockMvc.perform(multipart(UPLOAD)
                        .file(new MockMultipartFile("files", "a.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "b.png", "image/png", PNG))
                        .with(authenticatedUser()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].originalName").value("a.txt"))
                .andExpect(jsonPath("$.data[1].originalName").value("b.png"));
    }

    @Test
    void uploadWithoutFilesPartIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(multipart(UPLOAD).with(authenticatedUser()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadValidationFailuresMapToContractStatusCodes() throws Exception {
        when(attachmentService.upload(any(UploadAttachmentCommand.class)))
                .thenThrow(new AttachmentValidationException(Violation.UNSUPPORTED_TYPE,
                        "附件类型不受支持：payload.exe"));

        mockMvc.perform(multipart(UPLOAD)
                        .file(new MockMultipartFile("files", "payload.exe",
                                "application/octet-stream", new byte[] {1}))
                        .with(authenticatedUser()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_TYPE_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("payload.exe")));
    }

    @Test
    void deleteReturnsEmptySuccessEnvelope() throws Exception {
        mockMvc.perform(delete(DELETE).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void deleteMapsBoundAttachmentToConflict() throws Exception {
        // 已绑定附件不允许删除：这是状态冲突，不是参数错误
        doThrow(new AttachmentAlreadyBoundException()).when(attachmentService).delete(1L);

        mockMvc.perform(delete(DELETE).with(authenticatedUser()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_ALREADY_BOUND"));
    }

    @Test
    void deleteHidesMissingOrForeignAttachmentAsNotFound() throws Exception {
        doThrow(new AttachmentNotFoundException()).when(attachmentService).delete(1L);

        mockMvc.perform(delete(DELETE).with(authenticatedUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void unauthenticatedDeleteIsRejectedBeforeTouchingTheService() throws Exception {
        mockMvc.perform(delete(DELETE))
                .andExpect(status().isUnauthorized());
    }

    private static AttachmentDownloadResult download(String name, String contentType, byte[] content,
            boolean inline) {
        return new AttachmentDownloadResult(1L, name, contentType, content.length, inline,
                new ByteArrayInputStream(content));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedUser() {
        return user("tester").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
    }
}
