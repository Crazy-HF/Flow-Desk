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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 通用附件用例编排：独立上传（1～5 个，整体成败）、下载与内联预览。
 *
 * <p>上传的写入顺序按 {@code docs/engineering-readiness.md} 7.2：
 * 声明预检 → 逐个流式暂存 → 暂存后复核 → 事务内写全部元数据 → 逐个提交到最终存储 → 提交；
 * 失败补偿删除暂存或已提交的文件，补偿本身失败只告警、交由对账任务兜底。
 * 与工单侧（创建 / 补充）的编排逐字同构，只是这里的最终存储由 {@link AttachmentStorage} 决定是本地目录还是对象存储。</p>
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
    public List<AttachmentUploadResult> upload(UploadAttachmentCommand command) {
        List<MultipartFile> files = command.files();
        if (files.isEmpty()) {
            throw new AttachmentValidationException(Violation.EMPTY_FILE, "缺少上传文件");
        }

        // 1. 声明预检 + 逐个流式暂存（落盘同时得到真实字节数与 SHA-256）。
        List<StagedUpload> staged = stageAll(files);

        // 2. 事务内先写全部元数据，再逐个提交到最终存储——顺序与工单侧一致，全部在同一个事务里。
        List<String> promoted = new ArrayList<>();
        try {
            LocalDateTime createdAt = LocalDateTime.ofInstant(
                    clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            long uploadedBy = currentUploaderPort.currentUserId();

            List<Attachment> rows = new ArrayList<>(staged.size());
            for (StagedUpload item : staged) {
                rows.add(insertMetadata(item, uploadedBy, createdAt));
            }
            for (StagedUpload item : staged) {
                storage.promote(item.staged().storageKey(), item.type().contentType());
                promoted.add(item.staged().storageKey());
            }
            // 方法正常返回后事务代理才提交；提交失败只能在这里兜——对账任务是最终兜底。
            registerCommitFailureCleanup(promoted);

            List<AttachmentUploadResult> results = new ArrayList<>(staged.size());
            for (int index = 0; index < staged.size(); index++) {
                StagedUpload item = staged.get(index);
                Attachment row = rows.get(index);
                results.add(new AttachmentUploadResult(row.getId(), item.originalName(),
                        item.type().contentType(), item.staged().sizeBytes(), row.getCreatedAt()));
            }
            return List.copyOf(results);
        } catch (RuntimeException e) {
            compensate(promoted, staged);
            throw e;
        }
    }

    @Override
    public AttachmentDownloadResult download(long attachmentId) {
        return open(attachmentId, false);
    }

    @Override
    public AttachmentDownloadResult preview(long attachmentId) {
        return open(attachmentId, true);
    }

    /**
     * 下载与预览共用一条读取路径：可见性判定完全相同，唯一差别是「请求内联时能否内联」。
     *
     * <p>预览刻意不走签名 URL 重定向——那等于绕过应用授权，URL 一泄露就是公开文件；
     * 内容必须由后端在完成授权后代理输出。</p>
     */
    private AttachmentDownloadResult open(long attachmentId, boolean inlineRequested) {
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
                    inlineRequested && isInlineAllowed(row.getContentType()),
                    storage.openFinal(row.getStorageKey()));
        } catch (AttachmentStorageException e) {
            // 元数据在而文件缺失属完整性事故：记录告警，按不可见处理，不伪造文件（对账任务负责排查）。
            log.warn("附件元数据存在但文件不可读 attachmentId={} storageKey={}",
                    row.getId(), row.getStorageKey(), e);
            throw new AttachmentNotFoundException();
        }
    }

    /**
     * 内联裁决：只看数据库里**服务端识别出的**类型，且必须命中内联白名单
     * （JPEG / PNG / PDF / TXT）。绝不回显或信任客户端的声明类型。
     *
     * <p>HTML / SVG 这类可执行内容根本进不了上传白名单，这里是纵深防御的第二道闸：
     * 即使将来放宽上传白名单，预览也不会跟着放行。</p>
     */
    private static boolean isInlineAllowed(String contentType) {
        DetectedFileType type = DetectedFileType.fromContentType(contentType);
        return type != null && type.inlinePreviewable();
    }

    /** 上传者本人始终可见；其余走业务授权端口（不存在实现时视为不可见）。 */
    private boolean isVisibleTo(Attachment row, long requesterId) {
        if (row.getUploadedBy() != null && row.getUploadedBy() == requesterId) {
            return true;
        }
        return accessPort.map(port -> port.isVisibleTo(row.getId(), requesterId)).orElse(false);
    }

    /**
     * 声明预检 + 逐个暂存 + 暂存后复核；任一步失败先清理本次已暂存的文件再抛出，
     * 保证「整体失败不留半成品」（{@code docs/api-design.md} 7.1）。
     */
    private List<StagedUpload> stageAll(List<MultipartFile> files) {
        validator.validateFileCount(files.size());
        List<StagedUpload> staged = new ArrayList<>(files.size());
        try {
            long declaredTotal = 0;
            for (MultipartFile file : files) {
                String originalName = validator.validateOriginalName(file.getOriginalFilename());
                validator.validateFileSize(originalName, file.getSize());
                declaredTotal += file.getSize();
                staged.add(stageOne(originalName, file));
            }
            validator.validateTotalSize(declaredTotal);
            long actualTotal = staged.stream()
                    .mapToLong(item -> item.staged().sizeBytes())
                    .sum();
            validator.validateTotalSize(actualTotal);
            return List.copyOf(staged);
        } catch (RuntimeException e) {
            staged.forEach(item -> deleteStagedQuietly(item.staged().storageKey(), "上传预检失败补偿"));
            throw e;
        }
    }

    /**
     * 单个文件的暂存 + 暂存后复核。
     *
     * <p><b>复核失败必须先删掉本文件自己的暂存副本再抛出</b>：此时它还没被加进 {@code staged} 列表，
     * 外层补偿看不到它，会留下一个无人认领的暂存文件（对账任务虽然最终会清掉，但那是兜底，不是主路径）。
     * 工单侧 {@code TicketServiceImpl.stageOne} 是同一形状，同样处理。</p>
     */
    private StagedUpload stageOne(String originalName, MultipartFile file) {
        StagedAttachment staged;
        try {
            staged = storage.stage(file.getInputStream());
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "读取上传内容失败，originalName=" + originalName, e);
        }
        try {
            // 以服务端读到的事实复核大小，再做「扩展名 + 声明类型 + 识别结果」三重一致嗅探。
            validator.validateFileSize(originalName, staged.sizeBytes());
            DetectedFileType type = validator.validateContent(
                    originalName, file.getContentType(), storage.stagedPath(staged.storageKey()));
            return new StagedUpload(originalName, staged, type);
        } catch (RuntimeException e) {
            deleteStagedQuietly(staged.storageKey(), "暂存后复核失败补偿");
            throw e;
        }
    }

    private Attachment insertMetadata(StagedUpload item, long uploadedBy, LocalDateTime createdAt) {
        Attachment row = new Attachment();
        row.setStorageKey(item.staged().storageKey());
        row.setOriginalName(item.originalName());
        row.setContentType(item.type().contentType());
        row.setSizeBytes(item.staged().sizeBytes());
        row.setSha256(item.staged().sha256());
        row.setUploadedBy(uploadedBy);
        row.setCreatedAt(createdAt);
        attachmentMapper.insert(row);
        return row;
    }

    /**
     * 事务提交失败（发生在方法正常返回之后）时删除已提交的文件。
     *
     * <p>提交失败时事务必然回滚，注册的 {@code afterCompletion} 是唯一能看到它的位置；
     * 删除失败只告警，交由对账任务兜底。无法注册（例如测试上下文没有真实事务管理器）时跳过——
     * 那种场景下也不存在真实的提交失败。</p>
     */
    private void registerCommitFailureCleanup(List<String> storageKeys) {
        if (storageKeys.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<String> snapshot = List.copyOf(storageKeys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    snapshot.forEach(key -> deleteFinalQuietly(key, "事务未提交"));
                }
            }
        });
    }

    /** 失败补偿：已提交的文件按最终存储删除，未提交的删暂存；补偿失败只告警。 */
    private void compensate(List<String> promotedKeys, List<StagedUpload> staged) {
        Set<String> promoted = new HashSet<>(promotedKeys);
        promoted.forEach(key -> deleteFinalQuietly(key, "上传失败补偿"));
        staged.stream()
                .map(item -> item.staged().storageKey())
                .filter(key -> !promoted.contains(key))
                .forEach(key -> deleteStagedQuietly(key, "上传失败补偿"));
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

    /** 暂存完成、待写元数据与提交的单个附件。 */
    private record StagedUpload(String originalName, StagedAttachment staged, DetectedFileType type) {
    }
}
