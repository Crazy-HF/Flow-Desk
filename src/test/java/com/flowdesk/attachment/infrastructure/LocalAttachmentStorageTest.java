package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.support.AttachmentPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAttachmentStorageTest {

    @TempDir
    Path tempDir;

    private LocalAttachmentStorage newStorage() {
        return new LocalAttachmentStorage(AttachmentPropertiesFixture.local(tempDir.resolve("attachments")));
    }

    @Test
    void startupCreatesStagingAndFinalDirectories() {
        newStorage();

        assertThat(tempDir.resolve("attachments").resolve("staging")).isDirectory();
        assertThat(tempDir.resolve("attachments").resolve("files")).isDirectory();
    }

    @Test
    void startupFailsFastWhenRootIsAnExistingFile() throws IOException {
        Path existingFile = tempDir.resolve("not-a-directory");
        Files.writeString(existingFile, "x");

        assertThatThrownBy(() -> new LocalAttachmentStorage(AttachmentPropertiesFixture.local(existingFile)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stageReturnsRandomKeyWithSizeAndSha256() throws IOException {
        LocalAttachmentStorage storage = newStorage();
        byte[] content = "附件内容 attachment payload".getBytes(StandardCharsets.UTF_8);

        StagedAttachment staged = storage.stage(new ByteArrayInputStream(content));

        assertThat(staged.storageKey()).matches("[0-9a-f]{32}");
        assertThat(staged.sizeBytes()).isEqualTo(content.length);
        assertThat(staged.sha256()).isEqualTo(sha256Hex(content));
        assertThat(storage.stagedPath(staged.storageKey())).hasBinaryContent(content);
    }

    @Test
    void stageGeneratesDistinctKeysForSameContent() {
        LocalAttachmentStorage storage = newStorage();

        StagedAttachment first = storage.stage(new ByteArrayInputStream(new byte[] {1}));
        StagedAttachment second = storage.stage(new ByteArrayInputStream(new byte[] {1}));

        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
    }

    @Test
    void promoteRemovesStagingCopyAndStaysReadable() throws IOException {
        LocalAttachmentStorage storage = newStorage();
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        StagedAttachment staged = storage.stage(new ByteArrayInputStream(content));

        storage.promote(staged.storageKey(), "application/octet-stream");

        assertThat(storage.stagedPath(staged.storageKey())).doesNotExist();
        try (InputStream in = storage.openFinal(staged.storageKey())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void deletesAreIdempotentForBothDirectories() {
        LocalAttachmentStorage storage = newStorage();

        StagedAttachment staged = storage.stage(new ByteArrayInputStream(new byte[] {9}));
        storage.deleteStaged(staged.storageKey());
        storage.deleteStaged(staged.storageKey());
        assertThat(storage.stagedPath(staged.storageKey())).doesNotExist();

        StagedAttachment moved = storage.stage(new ByteArrayInputStream(new byte[] {9}));
        storage.promote(moved.storageKey(), "application/octet-stream");
        storage.deleteFinal(moved.storageKey());
        storage.deleteFinal(moved.storageKey());
        assertThatThrownBy(() -> storage.openFinal(moved.storageKey()))
                .isInstanceOf(AttachmentStorageException.class);
    }

    @Test
    void failedStageRemovesPartialFileFromStaging() throws IOException {
        LocalAttachmentStorage storage = newStorage();

        assertThatThrownBy(() -> storage.stage(new ExplodingInputStream()))
                .isInstanceOf(AttachmentStorageException.class);
        assertThat(stagingFileCount()).isZero();
    }

    @Test
    void rejectsKeysOutsideTheControlledShape() {
        LocalAttachmentStorage storage = newStorage();
        List<String> invalidKeys = List.of(
                "", "..", "../escape", "a/b",
                "0".repeat(31), "0".repeat(33),
                "ABCDEF0123456789ABCDEF0123456789",
                "g".repeat(32));

        for (String invalidKey : invalidKeys) {
            assertThatThrownBy(() -> storage.stagedPath(invalidKey))
                    .isInstanceOf(AttachmentStorageException.class);
            assertThatThrownBy(() -> storage.promote(invalidKey, "application/octet-stream"))
                    .isInstanceOf(AttachmentStorageException.class);
            assertThatThrownBy(() -> storage.deleteFinal(invalidKey))
                    .isInstanceOf(AttachmentStorageException.class);
        }
        assertThatThrownBy(() -> storage.stagedPath(null))
                .isInstanceOf(AttachmentStorageException.class);
    }

    @Test
    void stageRejectsNullContent() {
        LocalAttachmentStorage storage = newStorage();

        assertThatNullPointerException().isThrownBy(() -> storage.stage(null));
    }

    @Test
    void openFinalReportsMissingFileAsStorageFailure() {
        LocalAttachmentStorage storage = newStorage();

        assertThatThrownBy(() -> storage.openFinal("0".repeat(32)))
                .isInstanceOf(AttachmentStorageException.class);
    }

    private long stagingFileCount() throws IOException {
        try (var files = Files.list(tempDir.resolve("attachments").resolve("staging"))) {
            return files.count();
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 第一次批量读取返回一个字节，第二次抛错，用于验证暂存写入失败后的半成品清理。 */
    private static final class ExplodingInputStream extends InputStream {

        private int successfulReads = 0;

        @Override
        public int read() throws IOException {
            throw new IOException("boom");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (successfulReads++ == 0) {
                buffer[offset] = 1;
                return 1;
            }
            throw new IOException("boom");
        }
    }
}