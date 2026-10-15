package com.flowdesk.attachment.config;

/**
 * 附件最终存储的驱动类型（{@code flowdesk.attachment.storage}）。
 *
 * <p>暂存目录在两种驱动下都是本地目录（校验组件需要可随机访问的本地路径），
 * 差异只在「暂存内容提交到哪里」：{@link #LOCAL} 是同一根目录下的 files 子目录（原子移动），
 * {@link #S3} 是 S3 兼容对象存储（PutObject）。</p>
 */
public enum AttachmentStorageKind {

    /** 本地受控目录：开发与测试默认值；要求暂存与最终目录同卷且支持原子移动。 */
    LOCAL,

    /** S3 兼容对象存储：MinIO（本地/演示）或阿里云 OSS、任意 S3 兼容实现（生产）。 */
    S3
}
