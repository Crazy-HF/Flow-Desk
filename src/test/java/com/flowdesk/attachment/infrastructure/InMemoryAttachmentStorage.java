package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.config.AttachmentStorageKind;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版"最终存储"驱动（测试替身）：暂存仍在本地临时文件（校验组件需要可随机访问的路径），
 * 但 {@code promote} 之后内容只存在内存 Map 里——**没有文件移动、没有本地同卷要求**。
 *
 * <p>它证明 {@code AttachmentServiceImpl} 与工单侧编排只依赖 {@link AttachmentStorage} 的语义，
 * 不依赖"最终存储在本地磁盘上"这一实现细节。真实的对象存储路径由 {@code S3AttachmentStorageIT} 覆盖。</p>
 */
public class InMemoryAttachmentStorage extends AbstractAttachmentStorage {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, Instant> modifiedAt = new LinkedHashMap<>();

    public InMemoryAttachmentStorage(Path root) {
        super(root);
        try {
            initializeStaging();
        } catch (IOException e) {
            throw new IllegalStateException("内存驱动初始化暂存目录失败", e);
        }
    }

    @Override
    public AttachmentStorageKind kind() {
        return AttachmentStorageKind.S3;
    }

    @Override
    public void promote(String storageKey, String contentType) {
        Path source = stagedPath(storageKey);
        try {
            objects.put(storageKey, Files.readAllBytes(source));
            modifiedAt.put(storageKey, Instant.now());
            Files.deleteIfExists(source);
        } catch (IOException e) {
            throw new UncheckedIOException("内存驱动读取暂存文件失败：" + storageKey, e);
        }
    }

    @Override
    public void deleteFinal(String storageKey) {
        requireControlledKey(storageKey);
        objects.remove(storageKey);
        modifiedAt.remove(storageKey);
    }

    @Override
    public InputStream openFinal(String storageKey) {
        requireControlledKey(storageKey);
        byte[] content = objects.get(storageKey);
        if (content == null) {
            throw new AttachmentStorageException("附件文件缺失（内存驱动），storageKey=" + storageKey);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public List<StorageFile> listFinal() {
        return objects.keySet().stream()
                .map(key -> new StorageFile(key, modifiedAt.getOrDefault(key, Instant.EPOCH)))
                .toList();
    }

    @Override
    public boolean finalExists(String storageKey) {
        requireControlledKey(storageKey);
        return objects.containsKey(storageKey);
    }

    /** 供断言使用：当前最终存储里的对象数量。 */
    public int objectCount() {
        return objects.size();
    }

    private static void requireControlledKey(String storageKey) {
        if (!isControlledKey(storageKey)) {
            throw new AttachmentStorageException("非法附件存储标识：" + storageKey);
        }
    }
}
