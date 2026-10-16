package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.config.AttachmentStorageKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 兼容对象存储驱动（{@code docs/technical-architecture.md} 6.3）：MinIO（本地/演示）、
 * 阿里云 OSS 或任意 S3 兼容实现（生产）共用同一套代码，只换 endpoint 与凭据。
 *
 * <p>与 {@link LocalAttachmentStorage} 的差异只有「最终存储落在哪里」：本驱动仍然使用本地暂存目录
 * （{@link AbstractAttachmentStorage} 提供），{@link #promote} 把暂存文件 PutObject 到桶里，
 * 成功后删除暂存副本。因此**不要求本地同卷，也不要求文件系统支持原子移动**——
 * 同卷约束收窄为仅 local 驱动的要求。</p>
 *
 * <p>启动校验在构造时执行，失败即让应用启动失败：桶可达 + 探针对象可写、可读、可删。
 * 桶不存在或凭据错误在启动时暴露，而不是留到第一次上传。</p>
 *
 * <p>对象键即 {@code storageKey}（32 位小写十六进制），不使用前缀；列举时只认受控形状的对象名，
 * 桶内其它对象一律忽略——与本地驱动同一口径，对账任务因此不可能误删非附件对象。</p>
 */
public class S3AttachmentStorage extends AbstractAttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3AttachmentStorage.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final S3Client client;
    private final String bucket;

    public S3AttachmentStorage(AttachmentProperties properties, S3Client client) {
        super(properties.root());
        this.client = client;
        this.bucket = properties.s3().bucket();
        initialize();
    }

    @Override
    public AttachmentStorageKind kind() {
        return AttachmentStorageKind.S3;
    }

    @Override
    public void promote(String storageKey, String contentType) {
        Path source = stagedPath(storageKey);
        String objectContentType = contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPE
                : contentType;
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(storageKey)
                            .contentType(objectContentType)
                            .build(),
                    RequestBody.fromFile(source));
        } catch (S3Exception e) {
            throw new AttachmentStorageException(
                    "附件上传对象存储失败，storageKey=" + storageKey + "，bucket=" + bucket, e);
        }
        // 对象已经落桶，暂存副本只是冗余：删除失败不影响正确性，由对账的超期暂存清理兜底。
        deleteQuietly(source);
    }

    @Override
    public void deleteFinal(String storageKey) {
        if (!isControlledKey(storageKey)) {
            throw new AttachmentStorageException("非法附件存储标识：" + storageKey);
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (S3Exception e) {
            throw new AttachmentStorageException("对象存储删除失败，storageKey=" + storageKey, e);
        }
    }

    @Override
    public InputStream openFinal(String storageKey) {
        requireControlledKey(storageKey);
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (NoSuchKeyException e) {
            throw new AttachmentStorageException(
                    "附件文件缺失（数据库元数据与对象存储不一致），storageKey=" + storageKey, e);
        } catch (S3Exception e) {
            throw new AttachmentStorageException("对象存储读取失败，storageKey=" + storageKey, e);
        }
    }

    @Override
    public List<StorageFile> listFinal() {
        List<StorageFile> files = new ArrayList<>();
        try {
            for (S3Object object : client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).build()).contents()) {
                String key = object.key();
                if (!isControlledKey(key)) {
                    continue;
                }
                Instant modifiedAt = object.lastModified();
                files.add(new StorageFile(key, modifiedAt == null ? Instant.EPOCH : modifiedAt));
            }
        } catch (S3Exception e) {
            throw new AttachmentStorageException("对象存储列举失败，bucket=" + bucket, e);
        }
        return files;
    }

    @Override
    public boolean finalExists(String storageKey) {
        requireControlledKey(storageKey);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HEAD 对不存在的键返回不带响应体的 404，SDK 只给通用 S3Exception，按状态码判定。
            if (e.statusCode() == 404) {
                return false;
            }
            throw new AttachmentStorageException("对象存储探测失败，storageKey=" + storageKey, e);
        }
    }

    private void initialize() {
        try {
            initializeStaging();
        } catch (Exception e) {
            throw new IllegalStateException("附件暂存目录初始化失败（root=" + stagingRoot() + "）", e);
        }
        verifyBucketReachableAndWritable();
    }

    /**
     * 探针校验：写入探针对象 → 读回 → 删除。
     *
     * <p>只证明当前配置可用（桶存在、凭据有效、可写可读可删）；桶被删或凭据轮换需要重启，重启即重新校验。</p>
     */
    private void verifyBucketReachableAndWritable() {
        String probeKey = java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(probeKey).build(),
                    RequestBody.fromBytes(new byte[] {0}));
            try (InputStream in = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(probeKey).build())) {
                in.read();
            }
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(probeKey).build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "附件对象存储初始化失败（bucket=" + bucket + "）：请检查 endpoint / 凭据 / 桶是否存在且可写",
                    e);
        }
    }

    private static void requireControlledKey(String storageKey) {
        if (!isControlledKey(storageKey)) {
            throw new AttachmentStorageException("非法附件存储标识：" + storageKey);
        }
    }
}
