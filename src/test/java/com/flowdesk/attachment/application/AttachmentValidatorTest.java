package com.flowdesk.attachment.application;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.support.AttachmentPropertiesFixture;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.attachment.domain.AttachmentValidationException.Violation;
import com.flowdesk.attachment.domain.DetectedFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentValidatorTest {

    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TempDir
    Path tempDir;

    private final AttachmentProperties properties =
            AttachmentPropertiesFixture.local(Path.of(System.getProperty("java.io.tmpdir")));
    private final AttachmentValidator validator = new AttachmentValidator(properties);

    @Test
    void trimsOriginalNameAndEnforcesLengthBounds() {
        assertThat(validator.validateOriginalName("  report.pdf  ")).isEqualTo("report.pdf");
        assertThatCode(() -> validator.validateOriginalName("a".repeat(251) + ".txt"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateOriginalName("   "))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.INVALID_NAME));
        assertThatThrownBy(() -> validator.validateOriginalName("a".repeat(252) + ".txt"))
                .isInstanceOf(AttachmentValidationException.class);
        assertThatThrownBy(() -> validator.validateOriginalName(null))
                .isInstanceOf(AttachmentValidationException.class);
    }

    @Test
    void enforcesFileCountLimit() {
        assertThatCode(() -> validator.validateFileCount(properties.maxFilesPerRequest()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateFileCount(properties.maxFilesPerRequest() + 1))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.TOO_MANY_FILES));
    }

    @Test
    void enforcesSingleFileAndTotalSizeLimits() {
        assertThatThrownBy(() -> validator.validateFileSize("a.txt", 0))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.EMPTY_FILE));
        assertThatCode(() -> validator.validateFileSize("a.txt", properties.maxFileSize().toBytes()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateFileSize("a.txt", properties.maxFileSize().toBytes() + 1))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.FILE_TOO_LARGE));
        assertThatCode(() -> validator.validateTotalSize(properties.maxRequestTotalSize().toBytes()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateTotalSize(properties.maxRequestTotalSize().toBytes() + 1))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.TOTAL_TOO_LARGE));
    }

    @Test
    void detectsJpegPngAndPdfBySignature() throws IOException {
        assertThat(validateContent("photo.jpg", "image/jpeg", jpegBytes())).isEqualTo(DetectedFileType.JPEG);
        assertThat(validateContent("photo.jpeg", "image/jpeg", jpegBytes())).isEqualTo(DetectedFileType.JPEG);
        assertThat(validateContent("logo.png", "image/png", pngBytes())).isEqualTo(DetectedFileType.PNG);
        assertThat(validateContent("manual.pdf", "application/pdf", pdfBytes())).isEqualTo(DetectedFileType.PDF);
    }

    @Test
    void declaredTypeIgnoresCaseAndParameters() throws IOException {
        assertThat(validateContent("PHOTO.PNG", "IMAGE/PNG", pngBytes())).isEqualTo(DetectedFileType.PNG);
        assertThat(validateContent("notes.txt", "text/plain; charset=UTF-8", textBytes("hello")))
                .isEqualTo(DetectedFileType.TXT);
    }

    @Test
    void textWithoutSignatureIsDetectedWhenNoNulBytes() throws IOException {
        assertThat(validateContent("notes.txt", "text/plain", textBytes("hello 世界")))
                .isEqualTo(DetectedFileType.TXT);
    }

    @Test
    void detectsDocxAndXlsxOnlyWithOoxmlMarkers() throws IOException {
        assertThat(validateContent("plan.docx", DOCX_TYPE, ooxmlZip("word/document.xml")))
                .isEqualTo(DetectedFileType.DOCX);
        assertThat(validateContent("sheet.xlsx", XLSX_TYPE, ooxmlZip("xl/workbook.xml")))
                .isEqualTo(DetectedFileType.XLSX);
        assertThatThrownBy(() -> validateContent("plain.docx", DOCX_TYPE, zipOf("hello.txt")))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.UNSUPPORTED_TYPE));
    }

    @Test
    void rejectsContentThatDisagreesWithNameDeclarationOrContent() throws IOException {
        // 内容签名与扩展名不一致
        assertThatThrownBy(() -> validateContent("fake.png", "image/png", jpegBytes()))
                .isInstanceOf(AttachmentValidationException.class);
        // 声明媒体类型与扩展名不一致
        assertThatThrownBy(() -> validateContent("photo.jpg", "image/png", jpegBytes()))
                .isInstanceOf(AttachmentValidationException.class);
        // 通用类型不足以通过三重一致
        assertThatThrownBy(() -> validateContent("photo.jpg", "application/octet-stream", jpegBytes()))
                .isInstanceOf(AttachmentValidationException.class);
        // 含 NUL 字节的二进制伪装成文本
        assertThatThrownBy(() -> validateContent("hidden.txt", "text/plain", new byte[] {0, 1, 2, (byte) 0xFF}))
                .isInstanceOf(AttachmentValidationException.class);
        // 可执行文件改名并伪造声明
        assertThatThrownBy(() -> validateContent("trojan.pdf", "application/pdf",
                new byte[] {'M', 'Z', (byte) 0x90, 0}))
                .isInstanceOf(AttachmentValidationException.class);
    }

    @Test
    void rejectsTypesOutsideWhitelist() throws IOException {
        assertThatThrownBy(() -> validateContent("icon.svg", "image/svg+xml", textBytes("<svg/>")))
                .isInstanceOfSatisfying(AttachmentValidationException.class,
                        ex -> assertThat(ex.violation()).isEqualTo(Violation.UNSUPPORTED_TYPE));
        // ZIP 容器改名成 txt：签名嗅探识别为容器，与扩展名不一致
        assertThatThrownBy(() -> validateContent("archive.txt", "text/plain", zipOf("a.txt")))
                .isInstanceOf(AttachmentValidationException.class);
    }

    private DetectedFileType validateContent(String name, String declaredType, byte[] content) throws IOException {
        Path file = tempDir.resolve(UUID.randomUUID() + "-" + name);
        Files.write(file, content);
        return validator.validateContent(name, declaredType, file);
    }

    private static byte[] textBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'};
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] ooxmlZip(String documentEntry) throws IOException {
        return zipOf("[Content_Types].xml", documentEntry);
    }

    private static byte[] zipOf(String... entryNames) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String entryName : entryNames) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}