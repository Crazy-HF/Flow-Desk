package com.flowdesk.attachment.support;

import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.config.AttachmentStorageKind;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 附件配置的测试夹具：{@link AttachmentProperties} 是 record，字段较多，
 * 集中在这里避免每个用例重复写 8 个参数。
 */
public final class AttachmentPropertiesFixture {

    private AttachmentPropertiesFixture() {
    }

    /** 本地驱动配置：只指定 root，其余取默认值（与 application-test.yml 的口径一致）。 */
    public static AttachmentProperties local(Path root) {
        return new AttachmentProperties(root, null, AttachmentStorageKind.LOCAL, null, null, null, null, null);
    }

    /** 本地驱动配置 + 自定义保留期。 */
    public static AttachmentProperties local(Path root, Duration retention) {
        return new AttachmentProperties(root, retention, AttachmentStorageKind.LOCAL, null, null, null, null, null);
    }

    /** S3 驱动配置：指向本地 MinIO / 对象存储。 */
    public static AttachmentProperties s3(Path root, String endpoint, String bucket,
            String accessKey, String secretKey) {
        return new AttachmentProperties(root, null, AttachmentStorageKind.S3, null, null, null, null,
                new AttachmentProperties.S3(endpoint, bucket, "us-east-1", accessKey, secretKey, true));
    }
}
