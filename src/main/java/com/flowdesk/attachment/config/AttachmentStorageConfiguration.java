package com.flowdesk.attachment.config;

import com.flowdesk.attachment.infrastructure.AttachmentStorage;
import com.flowdesk.attachment.infrastructure.LocalAttachmentStorage;
import com.flowdesk.attachment.infrastructure.S3AttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * 附件存储驱动的装配（{@code flowdesk.attachment.storage}）。
 *
 * <p>驱动是**部署期选择**，不是运行期切换：同一进程里只存在一个 {@link AttachmentStorage} Bean。
 * {@code local} 是默认值，缺配置时也走它——测试与 CI 因此不依赖任何对象存储。</p>
 *
 * <p>两个驱动都在构造时完成启动自检（local：目录可写 + 同卷原子移动；s3：桶可达 + 可写可读可删），
 * 失败即让应用启动失败，把配置错误暴露在启动阶段。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AttachmentStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorageConfiguration.class);

    /** 本地受控目录驱动：开发与测试默认。 */
    @Bean
    @ConditionalOnProperty(name = "flowdesk.attachment.storage", havingValue = "local", matchIfMissing = true)
    public AttachmentStorage localAttachmentStorage(AttachmentProperties properties) {
        AttachmentStorage storage = new LocalAttachmentStorage(properties);
        log.info("附件存储驱动：local（root={}）", properties.root());
        return storage;
    }

    /** S3 兼容对象存储驱动：MinIO（本地/演示）或阿里云 OSS、任意 S3 兼容实现（生产）。 */
    @Bean
    @ConditionalOnProperty(name = "flowdesk.attachment.storage", havingValue = "s3")
    public AttachmentStorage s3AttachmentStorage(AttachmentProperties properties, S3Client attachmentS3Client) {
        AttachmentStorage storage = new S3AttachmentStorage(properties, attachmentS3Client);
        log.info("附件存储驱动：s3（bucket={}，endpoint={}，pathStyle={}）",
                properties.s3().bucket(), properties.s3().endpoint(), properties.s3().pathStyle());
        return storage;
    }

    /**
     * 附件专用的 S3 客户端：凭据只来自配置（环境变量注入），不使用默认凭据链，
     * 避免在开发机上意外拾取到别处的凭据。
     */
    @Bean
    @ConditionalOnProperty(name = "flowdesk.attachment.storage", havingValue = "s3")
    public S3Client attachmentS3Client(AttachmentProperties properties) {
        AttachmentProperties.S3 s3 = properties.s3();
        var builder = S3Client.builder()
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.pathStyle())
                        .build());
        if (s3.endpoint() != null) {
            builder.endpointOverride(URI.create(s3.endpoint()));
        }
        return builder.build();
    }
}
