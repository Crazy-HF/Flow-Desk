package com.flowdesk.attachment.infrastructure;

import com.flowdesk.attachment.application.port.AttachmentAccessPort;
import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.domain.Attachment;
import com.flowdesk.attachment.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 附件孤儿对账任务（{@code docs/engineering-readiness.md} 7.2；无 HTTP 入口）：
 * 超期暂存清理、最终目录以数据库为权威对账、超期未绑定附件清理、元数据在而文件缺失告警。
 *
 * <p>三条纪律：只处理受控形状的随机存储名（穿越防护由存储层在列举时完成）；
 * 元数据在而文件缺失属完整性事故——只告警，不删元数据、不伪造文件；
 * 未绑定附件清理要求存在业务授权端口实现（缺省时整段跳过，宁可堆积也不误删）。
 * v1 单实例：上传使用随机新键、清理只处理超过保留期的键，两者不会处理同一个暂存标识。</p>
 *
 * <p>每个条目独立尽力而为：单项失败只记告警并继续，不打断整轮对账。</p>
 */
@Component
public class AttachmentReconciliationTask {

    /** 每小时整点执行一轮；v1 演示规模下成本可忽略。 */
    static final String CRON = "0 0 * * * *";

    private static final Logger log = LoggerFactory.getLogger(AttachmentReconciliationTask.class);

    private final AttachmentStorage storage;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentProperties attachmentProperties;
    private final Optional<AttachmentAccessPort> accessPort;
    private final Clock clock;

    public AttachmentReconciliationTask(AttachmentStorage storage,
            AttachmentMapper attachmentMapper,
            AttachmentProperties attachmentProperties,
            Optional<AttachmentAccessPort> accessPort,
            Clock clock) {
        this.storage = storage;
        this.attachmentMapper = attachmentMapper;
        this.attachmentProperties = attachmentProperties;
        this.accessPort = accessPort;
        this.clock = clock;
    }

    /** 一轮完整对账；对外只有这一个入口，内部三段相互独立。 */
    @Scheduled(cron = CRON)
    public void reconcile() {
        Instant cutoffInstant = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS)
                .minus(attachmentProperties.retention());
        LocalDateTime cutoff = LocalDateTime.ofInstant(cutoffInstant, ZoneOffset.UTC);
        cleanExpiredStaging(cutoffInstant);
        reconcileFinalDirectory();
        cleanExpiredUnbound(cutoff);
    }

    /** 超期暂存清理：超过保留期的暂存文件不可能还有活动请求引用（v1 没有长于保留期的上传请求）。 */
    private void cleanExpiredStaging(Instant cutoffInstant) {
        int deleted = 0;
        for (StorageFile file : storage.listStaged()) {
            if (file.modifiedAt().isBefore(cutoffInstant) && deleteStaged(file.storageKey())) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("附件对账：清理超期暂存文件 {} 个", deleted);
        }
    }

    /** 最终目录对账：无对应已提交记录的文件删除；记录在而文件缺失只告警（完整性事故）。 */
    private void reconcileFinalDirectory() {
        Set<String> committedKeys = new HashSet<>(attachmentMapper.selectAllStorageKeys());
        for (StorageFile file : storage.listFinal()) {
            if (!committedKeys.contains(file.storageKey())) {
                deleteFinal(file.storageKey(), "最终目录孤儿清理");
            }
        }
        for (String storageKey : committedKeys) {
            if (!storage.finalExists(storageKey)) {
                log.warn("附件对账：数据库元数据存在但最终文件缺失（完整性事故，需人工排查） storageKey={}", storageKey);
            }
        }
    }

    /**
     * 超期未绑定附件清理：需要业务授权端口判定引用；没有实现时跳过。
     * 先删元数据再删文件——万一文件删除失败，留下的孤儿文件会在下一轮对账被清除（自愈）。
     */
    private void cleanExpiredUnbound(LocalDateTime cutoff) {
        if (accessPort.isEmpty()) {
            return;
        }
        int deleted = 0;
        for (Attachment candidate : attachmentMapper.selectCreatedBefore(cutoff)) {
            if (accessPort.get().isReferenced(candidate.getId())) {
                continue;
            }
            try {
                if (attachmentMapper.deleteById(candidate.getId()) != 1) {
                    log.warn("附件对账：未绑定附件元数据删除失败 attachmentId={}", candidate.getId());
                    continue;
                }
            } catch (RuntimeException exception) {
                log.warn("附件对账：未绑定附件元数据删除异常 attachmentId={}", candidate.getId(), exception);
                continue;
            }
            deleteFinal(candidate.getStorageKey(), "超期未绑定清理");
            deleted++;
        }
        if (deleted > 0) {
            log.info("附件对账：清理超期未绑定附件 {} 个", deleted);
        }
    }

    private boolean deleteStaged(String storageKey) {
        try {
            storage.deleteStaged(storageKey);
            return true;
        } catch (AttachmentStorageException exception) {
            log.warn("附件对账：删除暂存文件失败 storageKey={}", storageKey, exception);
            return false;
        }
    }

    private void deleteFinal(String storageKey, String reason) {
        try {
            storage.deleteFinal(storageKey);
        } catch (AttachmentStorageException exception) {
            log.warn("附件对账：删除最终文件失败（{}）storageKey={}", reason, storageKey, exception);
        }
    }
}