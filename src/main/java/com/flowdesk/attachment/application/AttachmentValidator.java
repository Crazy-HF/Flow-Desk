package com.flowdesk.attachment.application;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.attachment.domain.AttachmentValidationException.Violation;
import com.flowdesk.attachment.domain.DetectedFileType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 附件校验：限额、文件名与「扩展名 + 声明媒体类型 + 服务端识别结果」三重一致性
 * （{@code docs/api-design.md} 7.1；两类上传路径共用同一实例）。
 *
 * <p>限额来自 {@code flowdesk.attachment.max-*} 配置（不再硬编码）：调整限额只改配置、不改代码。
 * 注意 Spring 的 {@code spring.servlet.multipart.*} 是外层闸且为静态配置，调高这里必须同步调高外层闸。</p>
 *
 * <p>调用顺序配合 {@code docs/engineering-readiness.md} 7.2 的写入顺序：先做廉价的声明校验
 * （数量、文件名、声明大小与总量），流式写暂存后对**暂存内容**做尺寸复核与嗅探；
 * 任一校验失败由调用方删除暂存文件并让整个动作失败。</p>
 *
 * <p>嗅探不信任客户端 {@code Content-Type}：JPEG / PNG / PDF 看文件签名；DOCX / XLSX 是 OOXML 容器，
 * 必须同时看到 ZIP 签名、{@code [Content_Types].xml} 与 {@code word/document.xml}
 * （或 {@code xl/workbook.xml}），不能只看前 4 字节——容器按中央目录枚举，不展开数据，避免压缩炸弹；
 * TXT 无签名，按「前 8 KiB 无 NUL 字节」判定为文本。</p>
 */
@Component
public class AttachmentValidator {

    /** 签名读取与文本启发式共用的头部窗口。 */
    private static final int SNIFF_HEAD_BYTES = 8192;

    /** OOXML 容器最多枚举的条目数，防止异常容器拖慢校验。 */
    private static final int MAX_SCANNED_ZIP_ENTRIES = 512;

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};

    private static final String CONTENT_TYPES_ENTRY = "[Content_Types].xml";
    private static final String WORD_DOCUMENT_ENTRY = "word/document.xml";
    private static final String WORKBOOK_ENTRY = "xl/workbook.xml";

    private final AttachmentProperties properties;

    public AttachmentValidator(AttachmentProperties properties) {
        this.properties = properties;
    }

    /** 单次请求的文件数量上限；声明阶段预检与暂存后复核共用。 */
    public void validateFileCount(int fileCount) {
        int max = properties.maxFilesPerRequest();
        if (fileCount > max) {
            throw new AttachmentValidationException(Violation.TOO_MANY_FILES,
                    "单次最多上传 " + max + " 个附件，当前 " + fileCount + " 个");
        }
    }

    /**
     * 原始文件名：去首尾空白后 1～{@code max-original-name-length} 个字符。
     *
     * @return 去首尾空白后的文件名，供元数据保存与展示使用
     */
    public String validateOriginalName(String originalName) {
        int maxLength = properties.maxOriginalNameLength();
        String trimmed = originalName == null ? "" : originalName.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new AttachmentValidationException(Violation.INVALID_NAME,
                    "原始文件名去首尾空白后必须为 1～" + maxLength + " 个字符");
        }
        return trimmed;
    }

    /** 单文件大小：空文件拒绝，最大 {@code max-file-size}；声明大小与暂存后的实际大小使用同一判定。 */
    public void validateFileSize(String trimmedName, long sizeBytes) {
        if (sizeBytes == 0) {
            throw new AttachmentValidationException(Violation.EMPTY_FILE, "空文件不允许上传：" + trimmedName);
        }
        long maxBytes = properties.maxFileSize().toBytes();
        if (sizeBytes > maxBytes) {
            throw new AttachmentValidationException(Violation.FILE_TOO_LARGE,
                    "单个附件最大 " + describe(maxBytes) + "：" + trimmedName);
        }
    }

    /** 单次请求总大小：最大 {@code max-request-total-size}；声明总量与实际暂存总量使用同一判定。 */
    public void validateTotalSize(long totalBytes) {
        long maxBytes = properties.maxRequestTotalSize().toBytes();
        if (totalBytes > maxBytes) {
            throw new AttachmentValidationException(Violation.TOTAL_TOO_LARGE,
                    "单次上传附件总大小最大 " + describe(maxBytes));
        }
    }

    /**
     * 对暂存内容执行服务端识别，并校验与扩展名、声明媒体类型三者一致。
     *
     * @param trimmedName         已通过 {@link #validateOriginalName} 的文件名
     * @param declaredContentType 客户端声明的媒体类型，可为 {@code null}
     * @param stagedFile          暂存文件受控路径（来自 {@code AttachmentStorage.stagedPath}）
     * @return 服务端识别出的白名单类型；{@link DetectedFileType#contentType()} 即入库的规范媒体类型
     */
    public DetectedFileType validateContent(String trimmedName, String declaredContentType, Path stagedFile) {
        DetectedFileType byExtension = DetectedFileType.fromFileName(trimmedName);
        if (byExtension == null) {
            throw unsupported(trimmedName, "扩展名不在白名单");
        }
        DetectedFileType byDeclaration = DetectedFileType.fromDeclaredContentType(declaredContentType);
        if (byDeclaration != byExtension) {
            throw unsupported(trimmedName, "声明的媒体类型与扩展名不一致");
        }
        if (sniff(stagedFile) != byExtension) {
            throw unsupported(trimmedName, "服务端识别结果与扩展名/声明类型不一致");
        }
        return byExtension;
    }

    private static AttachmentValidationException unsupported(String trimmedName, String reason) {
        return new AttachmentValidationException(Violation.UNSUPPORTED_TYPE,
                "附件类型不受支持（" + reason + "；允许 JPEG、PNG、PDF、TXT、DOCX、XLSX）：" + trimmedName);
    }

    /** 以 MiB 为单位描述限额，便于与配置值对照（配置的 MB 即 MiB）。 */
    private static String describe(long bytes) {
        return (bytes / 1024 / 1024) + " MiB";
    }

    private DetectedFileType sniff(Path stagedFile) {
        byte[] head = readHead(stagedFile);
        if (head.length == 0) {
            return DetectedFileType.UNKNOWN;
        }
        if (startsWith(head, PNG_SIGNATURE)) {
            return DetectedFileType.PNG;
        }
        if (startsWith(head, JPEG_SIGNATURE)) {
            return DetectedFileType.JPEG;
        }
        if (startsWith(head, PDF_SIGNATURE)) {
            return DetectedFileType.PDF;
        }
        if (startsWith(head, ZIP_SIGNATURE)) {
            return sniffOoxml(stagedFile);
        }
        return isText(head) ? DetectedFileType.TXT : DetectedFileType.UNKNOWN;
    }

    private static byte[] readHead(Path stagedFile) {
        try (InputStream in = Files.newInputStream(stagedFile)) {
            return in.readNBytes(SNIFF_HEAD_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("读取暂存附件内容失败：" + stagedFile.getFileName(), e);
        }
    }

    /**
     * OOXML（DOCX / XLSX）判定：按中央目录枚举条目，不展开条目数据。
     * 必须看到 {@code [Content_Types].xml} 与 {@code word/document.xml}（或 {@code xl/workbook.xml}）。
     */
    private static DetectedFileType sniffOoxml(Path stagedFile) {
        boolean hasContentTypes = false;
        boolean hasWordDocument = false;
        boolean hasWorkbook = false;
        try (ZipFile zip = new ZipFile(stagedFile.toFile())) {
            var entries = zip.entries();
            int scanned = 0;
            while (scanned < MAX_SCANNED_ZIP_ENTRIES && entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                scanned++;
                if (CONTENT_TYPES_ENTRY.equals(entryName)) {
                    hasContentTypes = true;
                } else if (WORD_DOCUMENT_ENTRY.equals(entryName)) {
                    hasWordDocument = true;
                } else if (WORKBOOK_ENTRY.equals(entryName)) {
                    hasWorkbook = true;
                }
                if (hasContentTypes && (hasWordDocument || hasWorkbook)) {
                    break;
                }
            }
        } catch (ZipException e) {
            // 有 ZIP 签名但容器不可读（截断/伪造）：按未识别处理，由三重一致校验拒绝。
            return DetectedFileType.UNKNOWN;
        } catch (IOException e) {
            throw new UncheckedIOException("读取暂存附件容器失败：" + stagedFile.getFileName(), e);
        }
        if (!hasContentTypes) {
            return DetectedFileType.UNKNOWN;
        }
        if (hasWordDocument) {
            return DetectedFileType.DOCX;
        }
        if (hasWorkbook) {
            return DetectedFileType.XLSX;
        }
        return DetectedFileType.UNKNOWN;
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        return head.length >= signature.length
                && Arrays.equals(head, 0, signature.length, signature, 0, signature.length);
    }

    private static boolean isText(byte[] head) {
        for (byte value : head) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }
}
