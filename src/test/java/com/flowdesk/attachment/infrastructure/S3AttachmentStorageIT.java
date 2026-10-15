package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.config.AttachmentStorageConfiguration;
import com.flowdesk.attachment.config.AttachmentStorageKind;
import com.flowdesk.attachment.support.AttachmentPropertiesFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3 驱动对着**真实 S3 协议**跑（Testcontainers 起本地 MinIO，不是云）：
 * 覆盖对象上传、读取、列举、删除与启动自检，并验证「桶内非受控形状的对象一律忽略」这条安全口径。
 *
 * <p><b>CI 不依赖云</b>：MinIO 是本地容器；默认驱动仍是 local，本类只验证 s3 驱动本身。
 * 本机若拉不到镜像（镜像仓库不可用），本类会以明确的跳过原因中止，而不是伪装成通过——
 * 真跑没跑以 surefire/failsafe 报告为准。</p>
 *
 * <p><b>容器可覆盖</b>：默认起 {@code minio/minio:latest}（9000，{@code server /data}）。镜像源不可用时可用系统属性
 * 换成本机可达的 S3 兼容实现——**这正是存储抽象要证明的性质**（驱动不绑 MinIO）：</p>
 * <pre>
 *   -Dflowdesk.test.s3.image=adobe/s3mock:latest -Dflowdesk.test.s3.port=9090 -Dflowdesk.test.s3.container=plain
 * </pre>
 * <p>{@code container=plain} 表示"不需要 MinIO 的启动命令与 root 用户环境变量"的镜像。跳过原因里会打印实际使用的引用。</p>
 */
class S3AttachmentStorageIT {

    /** 默认 MinIO；见类注释，可用 {@code -Dflowdesk.test.s3.image} 覆盖。 */
    private static final DockerImageName S3_IMAGE = DockerImageName.parse(
            System.getProperty("flowdesk.test.s3.image", "minio/minio:latest"));
    /** 镜像内的服务端口；MinIO 9000，s3mock 9090。 */
    private static final int S3_PORT = Integer.getInteger("flowdesk.test.s3.port", 9000);
    /** {@code minio}（默认）或 {@code plain}（无启动命令 / 无 MinIO 环境变量的 S3 兼容镜像）。 */
    private static final boolean PLAIN_CONTAINER =
            "plain".equals(System.getProperty("flowdesk.test.s3.container", "minio"));
    private static final String ACCESS_KEY = "flowdesk-it-access";
    private static final String SECRET_KEY = "flowdesk-it-secret";
    private static final String BUCKET = "flowdesk-attachments-it";
    private static final String FOREIGN_KEY = "some-other-object.txt";

    private static final AttachmentStorageConfiguration CONFIGURATION = new AttachmentStorageConfiguration();

    @TempDir
    static Path tempDir;

    private static GenericContainer<?> objectStore;
    private static AttachmentProperties properties;

    @BeforeAll
    static void startObjectStore() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "本机没有可用的 Docker，跳过对象存储集成测试");
        GenericContainer<?> container = new GenericContainer<>(S3_IMAGE).withExposedPorts(S3_PORT);
        if (PLAIN_CONTAINER) {
            // plain 模式：镜像自带默认启动命令，也不认 MinIO 的 root 用户环境变量
            objectStore = container;
        } else {
            objectStore = container
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data");
        }
        try {
            objectStore.start();
        } catch (RuntimeException e) {
            Assumptions.abort("S3 兼容容器不可用（本机镜像仓库无法提供 " + S3_IMAGE + "），"
                    + "对象存储集成测试未执行：" + e.getMessage());
        }
        properties = AttachmentPropertiesFixture.s3(tempDir.resolve("attachments"),
                "http://" + objectStore.getHost() + ":" + objectStore.getMappedPort(S3_PORT),
                BUCKET, ACCESS_KEY, SECRET_KEY);
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void stopObjectStore() {
        if (objectStore != null) {
            objectStore.stop();
        }
    }

    @Test
    void promoteUploadsObjectAndRemovesTheStagingCopy() throws Exception {
        byte[] content = "对象存储里的附件内容".getBytes(StandardCharsets.UTF_8);
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            S3AttachmentStorage storage = new S3AttachmentStorage(properties, client);

            StagedAttachment staged = storage.stage(new ByteArrayInputStream(content));
            assertThat(storage.stagedPath(staged.storageKey())).exists();

            storage.promote(staged.storageKey(), "text/plain");

            assertThat(storage.stagedPath(staged.storageKey())).doesNotExist();
            assertThat(storage.finalExists(staged.storageKey())).isTrue();
            try (InputStream in = storage.openFinal(staged.storageKey())) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
            assertThat(storage.kind()).isEqualTo(AttachmentStorageKind.S3);
        }
    }

    @Test
    void listFinalIgnoresObjectsThatAreNotControlledAttachmentKeys() {
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            S3AttachmentStorage storage = new S3AttachmentStorage(properties, client);
            StagedAttachment staged = storage.stage(
                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
            storage.promote(staged.storageKey(), "text/plain");
            // 桶里放一个"别的系统"的对象：对账不能把它当成附件，更不能删它。
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(FOREIGN_KEY).build(),
                    RequestBody.fromString("not an attachment"));

            List<StorageFile> listed = storage.listFinal();

            assertThat(listed).extracting(StorageFile::storageKey).contains(staged.storageKey());
            assertThat(listed).extracting(StorageFile::storageKey).doesNotContain(FOREIGN_KEY);

            client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(FOREIGN_KEY).build());
        }
    }

    @Test
    void deletesAreIdempotentAndMissingObjectsAreReportedAsStorageFailure() {
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            S3AttachmentStorage storage = new S3AttachmentStorage(properties, client);
            StagedAttachment staged = storage.stage(
                    new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)));
            storage.promote(staged.storageKey(), "text/plain");

            storage.deleteFinal(staged.storageKey());
            storage.deleteFinal(staged.storageKey());

            assertThat(storage.finalExists(staged.storageKey())).isFalse();
            assertThatThrownBy(() -> storage.openFinal(staged.storageKey()))
                    .isInstanceOf(AttachmentStorageException.class);
        }
    }

    @Test
    void rejectsKeysOutsideTheControlledShape() {
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            S3AttachmentStorage storage = new S3AttachmentStorage(properties, client);

            for (String invalidKey : List.of("", "..", "../escape", "a/b", "ABCDEF0123456789ABCDEF0123456789")) {
                assertThatThrownBy(() -> storage.openFinal(invalidKey))
                        .isInstanceOf(AttachmentStorageException.class);
                assertThatThrownBy(() -> storage.deleteFinal(invalidKey))
                        .isInstanceOf(AttachmentStorageException.class);
            }
        }
    }

    @Test
    void startupProbeFailsFastWhenTheBucketDoesNotExist() {
        AttachmentProperties missingBucket = AttachmentPropertiesFixture.s3(tempDir.resolve("attachments-missing"),
                properties.s3().endpoint(), "flowdesk-bucket-that-does-not-exist", ACCESS_KEY, SECRET_KEY);

        try (S3Client client = CONFIGURATION.attachmentS3Client(missingBucket)) {
            assertThatThrownBy(() -> new S3AttachmentStorage(missingBucket, client))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("附件对象存储初始化失败");
        }
    }

    @Test
    void startupProbeFailsFastWhenCredentialsAreWrong() {
        Assumptions.assumeTrue(endpointEnforcesCredentials(),
                "当前 S3 端点不校验凭据（测试用 mock），跳过「错误凭据必须启动失败」——"
                        + "该断言依赖存储端实现签名校验，在 MinIO 上会真正执行");
        AttachmentProperties wrongCredentials = AttachmentPropertiesFixture.s3(tempDir.resolve("attachments-wrong"),
                properties.s3().endpoint(), BUCKET, "wrong-access-key", "wrong-secret-key");

        try (S3Client client = CONFIGURATION.attachmentS3Client(wrongCredentials)) {
            assertThatThrownBy(() -> new S3AttachmentStorage(wrongCredentials, client))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 存储端是否真的校验凭据。
     *
     * <p>MinIO 会拒绝错误签名；部分 S3 兼容实现（测试用 mock）接受任意凭据。在后者上断言
     * "错误凭据必须启动失败"是无意义的——它会失败，但那说明的是存储端没实现鉴权，不是驱动有问题。
     * 所以先探测：不校验就带原因跳过这一条，而不是留一条永远红或永远假的用例。</p>
     */
    private static boolean endpointEnforcesCredentials() {
        AttachmentProperties probe = AttachmentPropertiesFixture.s3(tempDir.resolve("attachments-credential-probe"),
                properties.s3().endpoint(), BUCKET, "wrong-access-key", "wrong-secret-key");
        try (S3Client client = CONFIGURATION.attachmentS3Client(probe)) {
            client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build());
            return false;
        } catch (S3Exception e) {
            return true;
        }
    }

    @Test
    void reconciliationStyleListingSeesCommittedObjects() {
        try (S3Client client = CONFIGURATION.attachmentS3Client(properties)) {
            S3AttachmentStorage storage = new S3AttachmentStorage(properties, client);
            StagedAttachment staged = storage.stage(
                    new ByteArrayInputStream("z".getBytes(StandardCharsets.UTF_8)));
            storage.promote(staged.storageKey(), "text/plain");

            assertThat(client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(BUCKET).build()).contents())
                    .extracting(object -> object.key())
                    .contains(staged.storageKey());
        }
    }
}
