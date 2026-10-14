package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.config.AttachmentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * 本地受控目录存储实现（{@code docs/technical-architecture.md} 6.3：v1 不引入对象存储）。
 *
 * <p>通用附件模块的基础设施实现（自 2026-10-12 方案 B 起独立于工单模块）。</p>
 *
 * <p>目录布局：root/staging（暂存）与 root/files（最终）。两者是同一根下的子目录，
 * 天然同卷，满足 {@code docs/engineering-readiness.md} 7.2 对原子移动的前提。</p>
 *
 * <p>启动校验在构造时执行，失败即让应用启动失败：
 * <ul>
 * <li>创建两个子目录；</li>
 * <li>写探针验证可写；</li>
 * <li>把探针从暂存目录原子移动到最终目录——成功即同时证明"同卷"与"文件系统支持原子移动"。</li>
 * </ul>
 * "位于 Web 根目录之外"无法在进程内验证，保留为部署清单项（engineering-readiness 第 5 节）。</p>
 */
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    /** 暂存子目录名：与最终目录同卷，只保存尚未进入数据库事务的文件。 */
    private static final String STAGING_DIRECTORY = "staging";
    /** 最终子目录名：以数据库 storage_key 为权威，对账任务扫描对象。 */
    private static final String FINAL_DIRECTORY = "files";

    /** 存储标识唯一形状：本组件生成的 32 位小写十六进制（16 随机字节）。 */
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final int STORAGE_KEY_BYTES = 16;
    private static final int BUFFER_SIZE = 8192;

    private final Path stagingRoot;
    private final Path finalRoot;
    private final SecureRandom random = new SecureRandom();

    public LocalAttachmentStorage(AttachmentProperties properties) {
        Path root = properties.root().toAbsolutePath().normalize();
        this.stagingRoot = root.resolve(STAGING_DIRECTORY);
        this.finalRoot = root.resolve(FINAL_DIRECTORY);
        initialize(root);
    }

    @Override
    public StagedAttachment stage(InputStream content) {
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
    public void moveToFinal(String storageKey) {
        Path source = resolveStaging(storageKey);
        Path target = resolveFinal(storageKey);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "附件原子移动失败（暂存与最终目录须同卷且文件系统支持原子移动），storageKey=" + storageKey, e);
        }
    }

    @Override
    public void deleteStaged(String storageKey) {
        delete(resolveStaging(storageKey), "暂存");
    }

    @Override
    public void deleteFinal(String storageKey) {
        delete(resolveFinal(storageKey), "最终");
    }

    @Override
    public Path stagedPath(String storageKey) {
        return resolveStaging(storageKey);
    }

    @Override
    public InputStream openFinal(String storageKey) {
        Path path = resolveFinal(storageKey);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new AttachmentStorageException(
                    "附件文件缺失（数据库元数据与磁盘不一致），storageKey=" + storageKey, e);
        } catch (IOException e) {
            throw new AttachmentStorageException("附件读取失败，storageKey=" + storageKey, e);
        }
    }

    @Override
    public List<StorageFile> listStaged() {
        return listControlledFiles(stagingRoot);
    }

    @Override
    public List<StorageFile> listFinal() {
        return listControlledFiles(finalRoot);
    }

    @Override
    public boolean finalExists(String storageKey) {
        return Files.isRegularFile(resolveFinal(storageKey));
    }

    /**
     * 只返回受控形状的随机存储名（{@code [0-9a-f]{32}}）：目录里出现其它名字一律忽略，
     * 对账任务因此不可能处理解析后越出根目录的名字。
     */
    private static List<StorageFile> listControlledFiles(Path directory) {
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

    private void initialize(Path root) {
        try {
            Files.createDirectories(stagingRoot);
            Files.createDirectories(finalRoot);
            verifyWritableAndAtomicMove();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "附件存储目录初始化失败（root=" + root + "）：目录须可写，且暂存与最终目录须位于同一卷并支持原子移动", e);
        }
    }

    /**
     * 探针校验：暂存目录写文件、原子移动到最终目录、再删除。
     *
     * <p>只能证明当前文件系统组合可用；换盘等变化需要重启，重启即重新校验。</p>
     */
    private void verifyWritableAndAtomicMove() throws IOException {
        Path probe = Files.createTempFile(stagingRoot, ".probe-", ".tmp");
        Path moved = finalRoot.resolve(probe.getFileName().toString());
        try {
            Files.write(probe, new byte[] {0});
            Files.move(probe, moved, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IllegalStateException(
                    "附件暂存目录与最终目录不支持原子移动：两者须位于同一文件系统/卷（staging=" + stagingRoot
                            + "，files=" + finalRoot + "）", e);
        } finally {
            deleteQuietly(probe);
            deleteQuietly(moved);
        }
    }

    private String newStorageKey() {
        byte[] bytes = new byte[STORAGE_KEY_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private Path resolveStaging(String storageKey) {
        return resolveInside(stagingRoot, storageKey);
    }

    private Path resolveFinal(String storageKey) {
        return resolveInside(finalRoot, storageKey);
    }

    /**
     * 穿越防护双层：key 只允许本组件生成的 32 位小写十六进制；
     * 即使将来放宽形状，解析 normalize 后仍必须位于对应根目录内
     * （{@code docs/engineering-readiness.md} 7.2 的路径穿越口径）。
     */
    private static Path resolveInside(Path directory, String storageKey) {
        if (storageKey == null || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new AttachmentStorageException("非法附件存储标识：" + storageKey);
        }
        Path resolved = directory.resolve(storageKey).normalize();
        if (!resolved.startsWith(directory)) {
            throw new AttachmentStorageException("附件存储标识越出受控目录：" + storageKey);
        }
        return resolved;
    }

    private static void delete(Path path, String label) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new AttachmentStorageException(label + "文件删除失败：" + path.getFileName(), e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 尽力而为的清理；残留文件没有数据库记录，交给对账任务处理。
        }
    }
}