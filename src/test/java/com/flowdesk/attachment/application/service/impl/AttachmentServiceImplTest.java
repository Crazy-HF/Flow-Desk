package com.flowdesk.attachment.application.service.impl;

import com.flowdesk.attachment.application.AttachmentValidator;
import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;
import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.domain.Attachment;
import com.flowdesk.attachment.domain.AttachmentNotFoundException;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.attachment.domain.AttachmentValidationException.Violation;
import com.flowdesk.attachment.infrastructure.AttachmentStorage;
import com.flowdesk.attachment.infrastructure.InMemoryAttachmentStorage;
import com.flowdesk.attachment.infrastructure.LocalAttachmentStorage;
import com.flowdesk.attachment.infrastructure.StagedAttachment;
import com.flowdesk.attachment.mapper.AttachmentMapper;
import com.flowdesk.attachment.support.AttachmentPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通用附件上传 / 下载 / 预览的编排测试。
 *
 * <p>覆盖三件在既有测试里没有的东西：**批量上传的整体成败与"失败精确到文件名"**、
 * **预览的内联白名单裁决**、以及**编排不依赖"最终存储在本地磁盘"**（用内存驱动跑同一套用例）。
 * 三重一致校验本身的细节由 {@code AttachmentValidatorTest} 覆盖，这里不重复。</p>
 */
class AttachmentServiceImplTest {

    private static final long UPLOADER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] PDF = "%PDF-1.7\n附件正文".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TXT = "纯文本附件内容".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 9};

    @TempDir
    Path tempDir;

    private AttachmentProperties properties;
    private LocalAttachmentStorage localStorage;
    private AttachmentMapper attachmentMapper;
    private AttachmentServiceImpl service;
    private final List<Attachment> inserted = new ArrayList<>();
    private final AtomicLong generatedIds = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        properties = AttachmentPropertiesFixture.local(tempDir.resolve("attachments"));
        localStorage = new LocalAttachmentStorage(properties);
        attachmentMapper = mock(AttachmentMapper.class);
        when(attachmentMapper.insert(any(Attachment.class))).thenAnswer(invocation -> {
            Attachment row = invocation.getArgument(0);
            row.setId(generatedIds.getAndIncrement());
            inserted.add(row);
            return 1;
        });
        // 读取按已写入的元数据解析，测试因此不用为每个 id 单独打桩。
        when(attachmentMapper.selectById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return inserted.stream().filter(row -> row.getId().equals(id)).findFirst().orElse(null);
        });
        service = new AttachmentServiceImpl(new AttachmentValidator(properties), localStorage, attachmentMapper,
                () -> UPLOADER_ID, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void uploadStoresEveryFileAndReturnsResultsInRequestOrder() throws IOException {
        List<AttachmentUploadResult> results = service.upload(new UploadAttachmentCommand(List.of(
                multipart("note.txt", "text/plain", TXT),
                multipart("shot.png", "image/png", PNG))));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(AttachmentUploadResult::originalName)
                .containsExactly("note.txt", "shot.png");
        assertThat(results).extracting(AttachmentUploadResult::contentType)
                .containsExactly("text/plain", "image/png");
        assertThat(results).extracting(AttachmentUploadResult::sizeBytes)
                .containsExactly((long) TXT.length, (long) PNG.length);
        assertThat(results).allSatisfy(result -> assertThat(result.createdAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        // 元数据两条、最终存储两份、暂存清空；内容与上传的一致。
        assertThat(inserted).hasSize(2);
        assertThat(inserted).allSatisfy(row -> assertThat(row.getUploadedBy()).isEqualTo(UPLOADER_ID));
        assertThat(localStorage.listFinal()).hasSize(2);
        assertThat(localStorage.listStaged()).isEmpty();
        assertThat(readFinal(inserted.get(0).getStorageKey())).isEqualTo(TXT);
        assertThat(readFinal(inserted.get(1).getStorageKey())).isEqualTo(PNG);
    }

    @Test
    void uploadFailsWholeRequestWithoutTraceAndNamesTheOffendingFile() {
        List<MultipartFile> files = List.of(
                multipart("ok.txt", "text/plain", TXT),
                multipart("payload.exe", "application/octet-stream", new byte[] {1, 2, 3}));

        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand(files)))
                .isInstanceOfSatisfying(AttachmentValidationException.class, exception -> {
                    assertThat(exception.violation()).isEqualTo(Violation.UNSUPPORTED_TYPE);
                    // 失败必须精确到文件名，前端才能逐条提示。
                    assertThat(exception.getMessage()).contains("payload.exe");
                });

        // 整体失败：没有元数据、没有暂存残留、没有已提交文件。
        assertThat(inserted).isEmpty();
        assertThat(localStorage.listStaged()).isEmpty();
        assertThat(localStorage.listFinal()).isEmpty();
    }

    @Test
    void uploadFailsWholeRequestWhenAFileIsEmptyAndNamesIt() {
        List<MultipartFile> files = List.of(
                multipart("ok.txt", "text/plain", TXT),
                multipart("blank.pdf", "application/pdf", new byte[0]));

        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand(files)))
                .isInstanceOfSatisfying(AttachmentValidationException.class, exception -> {
                    assertThat(exception.violation()).isEqualTo(Violation.EMPTY_FILE);
                    assertThat(exception.getMessage()).contains("blank.pdf");
                });

        assertThat(inserted).isEmpty();
        assertThat(localStorage.listFinal()).isEmpty();
    }

    @Test
    void uploadRejectsMoreFilesThanConfigured() {
        List<MultipartFile> files = new ArrayList<>();
        for (int index = 0; index <= properties.maxFilesPerRequest(); index++) {
            files.add(multipart("f" + index + ".txt", "text/plain", TXT));
        }

        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand(files)))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        exception -> assertThat(exception.violation()).isEqualTo(Violation.TOO_MANY_FILES));
        assertThat(localStorage.listStaged()).isEmpty();
    }

    @Test
    void uploadRejectsRequestWithoutAnyFile() {
        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand(List.of())))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        exception -> assertThat(exception.violation()).isEqualTo(Violation.EMPTY_FILE));
    }

    @Test
    void previewInlinesOnlyNonActiveWhitelistedTypes() {
        long txtId = store(1, "a.txt", "text/plain", TXT);
        long pngId = store(2, "a.png", "image/png", PNG);
        long jpegId = store(3, "a.jpg", "image/jpeg", JPEG);
        long pdfId = store(4, "a.pdf", "application/pdf", PDF);
        long docxId = store(5, "a.docx", DOCX_TYPE, docxContainer());
        long xlsxId = store(6, "a.xlsx", XLSX_TYPE, docxContainer());

        assertThat(service.preview(txtId).inline()).isTrue();
        assertThat(service.preview(pngId).inline()).isTrue();
        assertThat(service.preview(jpegId).inline()).isTrue();
        assertThat(service.preview(pdfId).inline()).isTrue();
        // Office 文档一律降级为下载。
        assertThat(service.preview(docxId).inline()).isFalse();
        assertThat(service.preview(xlsxId).inline()).isFalse();
        // 显式下载永远不内联，即使类型本身可以内联。
        assertThat(service.download(pngId).inline()).isFalse();
    }

    @Test
    void previewReturnsReadableStreamOfStoredContent() throws IOException {
        long pngId = store(1, "a.png", "image/png", PNG);

        AttachmentDownloadResult result = service.preview(pngId);

        assertThat(result.originalName()).isEqualTo("a.png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.sizeBytes()).isEqualTo(PNG.length);
        try (InputStream in = result.content()) {
            assertThat(in.readAllBytes()).isEqualTo(PNG);
        }
    }

    @Test
    void previewAndDownloadHideAttachmentsUploadedByOthers() {
        long otherUsersAttachment = store(1, "a.png", "image/png", PNG);
        inserted.get(0).setUploadedBy(UPLOADER_ID + 1);

        assertThatThrownBy(() -> service.preview(otherUsersAttachment))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.download(otherUsersAttachment))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void previewAndDownloadReportUnknownIdAsNotFound() {
        when(attachmentMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.preview(404L)).isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.download(404L)).isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void previewFallsBackToNotFoundWhenFileIsMissing() {
        long pngId = store(1, "a.png", "image/png", PNG);
        localStorage.deleteFinal(inserted.get(0).getStorageKey());

        // 元数据在而文件缺失属完整性事故：对外按不可见处理，不暴露内部状态。
        assertThatThrownBy(() -> service.preview(pngId)).isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void uploadAndPreviewWorkWithADriverThatIsNotTheLocalDisk() throws IOException {
        // 同一套编排跑在"最终存储不是本地文件"的驱动上：证明不依赖磁盘实现细节。
        AttachmentStorage objectStorage = new InMemoryAttachmentStorage(tempDir.resolve("object-store"));
        AttachmentServiceImpl objectStorageService = new AttachmentServiceImpl(
                new AttachmentValidator(properties), objectStorage, attachmentMapper,
                () -> UPLOADER_ID, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC));

        List<AttachmentUploadResult> results = objectStorageService.upload(new UploadAttachmentCommand(List.of(
                multipart("shot.png", "image/png", PNG))));

        assertThat(results).hasSize(1);
        assertThat(objectStorage.listFinal()).hasSize(1);
        assertThat(objectStorage.listStaged()).isEmpty();
        assertThat(objectStorage.kind().name()).isEqualTo("S3");

        AttachmentDownloadResult preview = objectStorageService.preview(results.get(0).id());
        assertThat(preview.inline()).isTrue();
        try (InputStream in = preview.content()) {
            assertThat(in.readAllBytes()).isEqualTo(PNG);
        }
    }

    private long store(long id, String originalName, String contentType, byte[] content) {
        StagedAttachment staged = localStorage.stage(new ByteArrayInputStream(content));
        localStorage.promote(staged.storageKey(), contentType);
        Attachment row = new Attachment();
        row.setId(id);
        row.setStorageKey(staged.storageKey());
        row.setOriginalName(originalName);
        row.setContentType(contentType);
        row.setSizeBytes((long) content.length);
        row.setSha256(staged.sha256());
        row.setUploadedBy(UPLOADER_ID);
        row.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        inserted.add(row);
        when(attachmentMapper.selectById(id)).thenReturn(row);
        return id;
    }

    private byte[] readFinal(String storageKey) throws IOException {
        try (InputStream in = localStorage.openFinal(storageKey)) {
            return in.readAllBytes();
        }
    }

    private static MultipartFile multipart(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    /** 最小可识别的 OOXML 容器：ZIP 签名 + [Content_Types].xml + word/document.xml。 */
    private static byte[] docxContainer() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
