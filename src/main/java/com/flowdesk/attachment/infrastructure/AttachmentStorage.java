package com.flowdesk.attachment.infrastructure;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 受控附件文件存储出口：调用方只依赖本接口，不感知文件实际落在本地磁盘的哪个目录。
 *
 * <p>通用附件模块的基础设施接口（自 2026-10-12 方案 B 起独立于工单模块），
 * 只处理文件本身，不感知任何业务归属。</p>
 *
 * <p>写入分两段（{@code docs/engineering-readiness.md} 7.2）：先把上传流写入暂存目录，
 * 校验通过且数据库元数据在同一事务内写入后，再逐个原子移动到最终目录；
 * 中途失败的"已移动清单"逆向清理由编排层负责，本接口只提供单文件原语。</p>
 *
 * <p>所有按 {@code storageKey} 定位的操作都经过路径穿越防护：key 必须是本组件生成的
 * 32 位小写十六进制串，解析 normalize 后还必须仍位于对应子目录内。
 * 客户端原始文件名永远不参与真实磁盘路径拼接。</p>
 */
public interface AttachmentStorage {

    /**
     * 把上传流写入暂存目录，同时计算字节数与 SHA-256 摘要。
     *
     * <p>不关闭调用方传入的流；写入中途失败会尽力清理半成品暂存文件后抛
     * {@link AttachmentStorageException}。</p>
     */
    StagedAttachment stage(InputStream content);

    /** 把暂存文件原子移动到最终目录；失败（含目录不同卷或文件系统不支持原子移动）抛异常，由编排层回滚。 */
    void moveToFinal(String storageKey);

    /** 删除暂存文件；文件不存在时不做任何事。用于失败补偿与对账。 */
    void deleteStaged(String storageKey);

    /** 删除最终文件；文件不存在时不做任何事。用于失败补偿与对账。 */
    void deleteFinal(String storageKey);

    /**
     * 暂存文件的受控路径，供校验组件在移动前读取内容（{@code docs/engineering-readiness.md} 7.2 的
     * 「先暂存、再校验、后移动」顺序，含 OOXML 容器按中央目录枚举的需要）。
     * 调用方只能把该路径交给校验组件读取，不得据此拼接其他路径。
     */
    Path stagedPath(String storageKey);

    /** 打开最终文件流用于下载；文件缺失或不可读时抛 {@link AttachmentStorageException}。 */
    InputStream openFinal(String storageKey);

    /** 列出暂存目录中受控形状的存储文件（对账任务用；非随机存储名一律忽略）。 */
    List<StorageFile> listStaged();

    /** 列出最终目录中受控形状的存储文件（对账任务用；非随机存储名一律忽略）。 */
    List<StorageFile> listFinal();

    /** 最终文件是否存在（对账任务用：元数据在而文件缺失属完整性事故，只告警、不伪造）。 */
    boolean finalExists(String storageKey);
}