package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.config.AttachmentStorageKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 本地受控目录存储实现（{@code docs/technical-architecture.md} 6.3；开发与测试的默认驱动）。
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
 * <b>同卷约束只属于本驱动</b>：{@link S3AttachmentStorage} 不要求本地同卷，改为对象存储连通性探针。
 * "位于 Web 根目录之外"无法在进程内验证，保留为部署清单项（engineering-readiness 第 5 节）。</p>
 */
public class LocalAttachmentStorage extends AbstractAttachmentStorage {

    /** 最终子目录名：以数据库 storage_key 为权威，对账任务扫描对象。 */
    private static final String FINAL_DIRECTORY = "files";

    private final Path finalRoot;

    public LocalAttachmentStorage(AttachmentProperties properties) {
        super(properties.root());
        this.finalRoot = properties.root().toAbsolutePath().normalize().resolve(FINAL_DIRECTORY);
        initialize(properties.root().toAbsolutePath().normalize());
    }

    @Override
    public AttachmentStorageKind kind() {
        return AttachmentStorageKind.LOCAL;
    }

    @Override
    public void promote(String storageKey, String contentType) {
        // 本地驱动不需要 contentType：媒体类型已经落在数据库元数据里，磁盘文件不带类型信息。
        Path source = stagedPath(storageKey);
        Path target = resolveFinal(storageKey);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "附件原子移动失败（暂存与最终目录须同卷且文件系统支持原子移动），storageKey=" + storageKey, e);
        }
    }

    @Override
    public void deleteFinal(String storageKey) {
        Path path = resolveFinal(storageKey);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new AttachmentStorageException("最终文件删除失败：" + storageKey, e);
        }
    }

    @Override
    public InputStream openFinal(String storageKey) {
        return openLocalFinal(resolveFinal(storageKey), storageKey);
    }

    @Override
    public List<StorageFile> listFinal() {
        return listControlledFiles(finalRoot);
    }

    @Override
    public boolean finalExists(String storageKey) {
        return Files.isRegularFile(resolveFinal(storageKey));
    }

    private Path resolveFinal(String storageKey) {
        return resolveInside(finalRoot, storageKey);
    }

    private void initialize(Path root) {
        try {
            initializeStaging();
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
        Path probe = Files.createTempFile(stagingRoot(), ".probe-", ".tmp");
        Path moved = finalRoot.resolve(probe.getFileName().toString());
        try {
            Files.write(probe, new byte[] {0});
            Files.move(probe, moved, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IllegalStateException(
                    "附件暂存目录与最终目录不支持原子移动：两者须位于同一文件系统/卷（staging=" + stagingRoot()
                            + "，files=" + finalRoot + "）", e);
        } finally {
            deleteQuietly(probe);
            deleteQuietly(moved);
        }
    }
}
