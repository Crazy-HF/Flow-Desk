package com.flowdesk.attachment.application.service.impl;

import com.flowdesk.attachment.application.AttachmentValidator;
import com.flowdesk.attachment.application.command.UploadAttachmentCommand;
import com.flowdesk.attachment.application.port.AttachmentAccessPort;
import com.flowdesk.attachment.application.port.CurrentUploaderPort;
import com.flowdesk.attachment.application.result.AttachmentDownloadResult;
import com.flowdesk.attachment.application.result.AttachmentUploadResult;
import com.flowdesk.attachment.application.service.AttachmentService;
import com.flowdesk.attachment.domain.Attachment;
import com.flowdesk.attachment.domain.AttachmentNotFoundException;
import com.flowdesk.attachment.domain.AttachmentValidationException;
import com.flowdesk.attachment.domain.AttachmentValidationException.Violation;
import com.flowdesk.attachment.domain.DetectedFileType;
import com.flowdesk.attachment.infrastructure.AttachmentStorage;
import com.flowdesk.attachment.infrastructure.AttachmentStorageException;
import com.flowdesk.attachment.infrastructure.StagedAttachment;
import com.flowdesk.attachment.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 通用附件用例编排：独立上传与下载。
 *
 * <p>上传的写入顺序按 {@code docs/engineering-readiness.md} 7.2 的单文件特化：
 * 声明预检 → 流式暂存 → 暂存后复核 → 事务内写元数据 → 原子移动到最终目录 → 提交；
 * 失败补偿删除暂存或已移动文件，补偿本身失败只告警、交由对账任务兜底（步骤 7）。</p>
 */
@Service
public class AttachmentServiceImpl implements AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentServiceImpl.class);

    private final AttachmentValidator validator;
    private final AttachmentStorage storage;
    private final AttachmentMapper attachmentMapper;
    private final CurrentUploaderPort currentUploaderPort;
    private final Optional<AttachmentAccessPort> accessPort;
    private final Clock clock;

    public AttachmentServiceImpl(AttachmentValidator validator,
            AttachmentStorage storage,
            AttachmentMapper attachmentMapper,
            CurrentUploaderPort currentUploaderPort,
            Optional<AttachmentAccessPort> accessPort,
            Clock clock) {
        this.validator = validator;
        this.storage = storage;
        this.attachmentMapper = attachmentMapper;
        this.currentUploaderPort = currentUploaderPort;
        this.accessPort = accessPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AttachmentUploadResult upload(UploadAttachmentCommand command) {
        MultipartFile file = command.file();
        if (file == null) {
            throw new AttachmentValidationException(Violation.EMPTY_FILE, "缺少上传文件");
        }

        // 1. 廉价预检：文件名与声明大小，尽早拒绝明显非法的请求（尚未落盘）。
        String originalName = validator.validateOriginalName(file.getOriginalFilename());
        validator.validateFileSize(originalName, file.getSize());

        // 2. 流式写暂存：落盘的同时得到真实字节数与 SHA-256。
        StagedAttachment staged = stage(file);

        boolean moved = false;
        try {
            // 3. 以服务端读到的事实复核大小，再做「扩展名 + 声明类型 + 识别结果」三重一致嗅探。
            validator.validateFileSize(originalName, staged.sizeBytes());
            DetectedFileType fileType = validator.validateContent(
                    originalName, file.getContentType(), storage.stagedPath(staged.storageKey()));

            // 4. 事务内写元数据，然后把暂存文件原子移动到最终目录；提交由事务代理在方法返回后进行。
            Attachment row = new Attachment();
            row.setStorageKey(staged.storageKey());
            row.setOriginalName(originalName);
            row.setContentType(fileType.contentType());
            row.setSizeBytes(staged.sizeBytes());
            row.setSha256(staged.sha256());
            row.setUploadedBy(currentUploaderPort.currentUserId());
            row.setCreatedAt(LocalDateTime.ofInstant(
                    clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
            attachmentMapper.insert(row);

            storage.moveToFinal(staged.storageKey());
            moved = true;
            // 方法正常返回后事务代理才提交；提交失败只能在这里兜——对账任务（步骤 7）是最终兜底。
            registerCommitFailureCleanup(staged.storageKey());

            return new AttachmentUploadResult(
                    row.getId(), originalName, fileType.contentType(), staged.sizeBytes(), row.getCreatedAt());
        } catch (RuntimeException e) {
            compensate(staged.storageKey(), moved);
            throw e;
        }
    }

    @Override
    public AttachmentDownloadResult download(long attachmentId) {
        Attachment row = attachmentMapper.selectById(attachmentId);
        if (row == null) {
            throw new AttachmentNotFoundException();
        }
        long requesterId = currentUploaderPort.currentUserId();
        if (!isVisibleTo(row, requesterId)) {
            throw new AttachmentNotFoundException();
        }
        try {
            return new AttachmentDownloadResult(
                    row.getId(), row.getOriginalName(), row.getContentType(), row.getSizeBytes(),
                    storage.openFinal(row.getStorageKey()));
        } catch (AttachmentStorageException e) {
            // 元数据在而文件缺失属完整性事故：记录告警，按不可见处理，不伪造文件（对账任务负责排查）。
            log.warn("附件元数据存在但文件不可读 attachmentId={} storageKey={}",
                    row.getId(), row.getStorageKey(), e);
            throw new AttachmentNotFoundException();
        }
    }

    /** 上传者本人始终可见；其余走业务授权端口（不存在实现时视为不可见）。 */
    private boolean isVisibleTo(Attachment row, long requesterId) {
        if (row.getUploadedBy() != null && row.getUploadedBy() == requesterId) {
            return true;
        }
        return accessPort.map(port -> port.isVisibleTo(row.getId(), requesterId)).orElse(false);
    }

    private StagedAttachment stage(MultipartFile file) {
        try {
            return storage.stage(file.getInputStream());
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "读取上传内容失败，originalName=" + file.getOriginalFilename(), e);
        }
    }

    /**
     * 事务提交失败（发生在方法正常返回之后）时删除已移动的最终文件。
     *
     * <p>提交失败时事务必然回滚，注册的 {@code afterCompletion} 是唯一能看到它的位置；
     * 删除失败只告警，交由对账任务兜底。无法注册（例如测试上下文没有真实事务管理器）时跳过——
     * 那种场景下也不存在真实的提交失败。</p>
     */
    private void registerCommitFailureCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteFinalQuietly(storageKey, "事务未提交");
                }
            }
        });
    }

    private void compensate(String storageKey, boolean moved) {
        if (moved) {
            deleteFinalQuietly(storageKey, "上传失败补偿");
        } else {
            deleteStagedQuietly(storageKey, "上传失败补偿");
        }
    }

    private void deleteFinalQuietly(String storageKey, String reason) {
        try {
            storage.deleteFinal(storageKey);
        } catch (AttachmentStorageException e) {
            log.warn("附件补偿删除最终文件失败，交由对账处理 storageKey={} reason={}", storageKey, reason, e);
        }
    }

    private void deleteStagedQuietly(String storageKey, String reason) {
        try {
            storage.deleteStaged(storageKey);
        } catch (AttachmentStorageException e) {
            log.warn("附件补偿删除暂存文件失败，交由对账处理 storageKey={} reason={}", storageKey, reason, e);
        }
    }
}