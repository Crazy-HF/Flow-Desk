package com.flowdesk.attachment.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 存储驱动共用部分：**本地暂存**（{@code docs/technical-architecture.md} 6.3）。
 *
 * <p>两种驱动都用本地暂存目录承载上传流，因为校验组件需要可随机访问的本地路径
 * （魔数嗅探、OOXML 容器按中央目录枚举）。本类只实现 {@link AttachmentStorage} 中与暂存相关的方法，
 * 「提交到最终存储」及其之后的操作由各驱动实现。</p>
 *
 * <p>受控形状与穿越防护在这一层统一收口：存储标识只能是本组件生成的 32 位小写十六进制，
 * 解析 normalize 后还必须位于暂存根目录内；对账列举时也只认该形状，因此不可能处理越出根目录的名字。</p>
 */
abstract class AbstractAttachmentStorage implements AttachmentStorage {

    /** 暂存子目录名：只保存尚未进入数据库事务的文件。 */
    static final String STAGING_DIRECTORY = "staging";

    /** 存储标识唯一形状：本组件生成的 32 位小写十六进制（16 随机字节）。 */
    static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("[0-9a-f]{32}");

    private static final int STORAGE_KEY_BYTES = 16;
    private static final int BUFFER_SIZE = 8192;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path stagingRoot;

    protected AbstractAttachmentStorage(Path root) {
        this.stagingRoot = root.toAbsolutePath().normalize().resolve(STAGING_DIRECTORY);
    }

    /** 创建暂存目录；失败即让应用启动失败（调用方负责把异常转成清晰的启动错误）。 */
    protected final void initializeStaging() throws IOException {
        Files.createDirectories(stagingRoot);
    }

    protected final Path stagingRoot() {
        return stagingRoot;
    }

    @Override
    public final StagedAttachment stage(InputStream content) {
        Objects.requireNonNull(content, "content");
        String storageKey = newStorageKey();
        Path target = resolveStaging(storageKey);
        MessageDigest digest = sha256Digest();
        long sizeBytes = 0;
        try {
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    sizeBytes += read;
                }
            }
        } catch (IOException e) {
            // 半成品暂存清理是尽力而为；即使残留也不会成为"已提交附件"，由对账任务兜底。
            deleteQuietly(target);
            throw new AttachmentStorageException("附件暂存写入失败，storageKey=" + storageKey, e);
        }
        return new StagedAttachment(storageKey, sizeBytes, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public final void deleteStaged(String storageKey) {
        try {
            Files.deleteIfExists(resolveStaging(storageKey));
        } catch (IOException e) {
            throw new AttachmentStorageException("暂存文件删除失败：" + storageKey, e);
        }
    }

    @Override
    public final Path stagedPath(String storageKey) {
        return resolveStaging(storageKey);
    }

    @Override
    public final List<StorageFile> listStaged() {
        return listControlledFiles(stagingRoot);
    }

    /** 打开最终存储中的文件；对象存储驱动复用同一异常语义。 */
    protected static InputStream openLocalFinal(Path path, String storageKey) {
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new AttachmentStorageException(
                    "附件文件缺失（数据库元数据与存储不一致），storageKey=" + storageKey, e);
        } catch (IOException e) {
            throw new AttachmentStorageException("附件读取失败，storageKey=" + storageKey, e);
        }
    }

    /**
     * 只返回受控形状的随机存储名（{@code [0-9a-f]{32}}）：目录里出现其它名字一律忽略，
     * 对账任务因此不可能处理解析后越出根目录的名字。
     */
    protected static List<StorageFile> listControlledFiles(Path directory) {
        try (var paths = Files.list(directory)) {
            List<StorageFile> files = new ArrayList<>();
            paths.forEach(path -> {
                String name = path.getFileName().toString();
                if (STORAGE_KEY_PATTERN.matcher(name).matches() && Files.isRegularFile(path)) {
                    try {
                        files.add(new StorageFile(name, Files.getLastModifiedTime(path).toInstant()));
                    } catch (IOException exception) {
                        throw new UncheckedIOException("读取受控文件时间失败：" + name, exception);
                    }
                }
            });
            return files;
        } catch (IOException e) {
            throw new AttachmentStorageException("扫描受控目录失败：" + directory, e);
        }
    }

    protected static boolean isControlledKey(String storageKey) {
        return storageKey != null && STORAGE_KEY_PATTERN.matcher(storageKey).matches();
    }

    /**
     * 穿越防护双层：key 只允许本组件生成的 32 位小写十六进制；
     * 即使将来放宽形状，解析 normalize 后仍必须位于对应根目录内
     * （{@code docs/engineering-readiness.md} 7.2 的路径穿越口径）。
     */
    protected static Path resolveInside(Path directory, String storageKey) {
        if (!isControlledKey(storageKey)) {
            throw new AttachmentStorageException("非法附件存储标识：" + storageKey);
        }
        Path resolved = directory.resolve(storageKey).normalize();
        if (!resolved.startsWith(directory)) {
            throw new AttachmentStorageException("附件存储标识越出受控目录：" + storageKey);
        }
        return resolved;
    }

    protected static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力而为的清理；残留文件没有数据库记录，交给对账任务处理。
        }
    }

    private Path resolveStaging(String storageKey) {
        return resolveInside(stagingRoot, storageKey);
    }

    private static String newStorageKey() {
        byte[] bytes = new byte[STORAGE_KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
