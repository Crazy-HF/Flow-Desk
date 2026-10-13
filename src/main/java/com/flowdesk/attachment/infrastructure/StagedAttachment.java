package com.flowdesk.attachment.infrastructure;

/**
 * 已完成暂存写入的附件结果：存储标识、字节数与内容摘要。
 *
 * <p>附件元数据写入 {@code attachment} 表前必须已经取得本记录；数据库只保存这里的结果，
 * 文件内容由存储组件按 {@code storageKey} 管理。</p>
 *
 * @param storageKey 服务端随机存储标识（32 位小写十六进制），全局唯一，客户端输入不参与生成
 * @param sizeBytes  文件字节数（是否为 0 由校验层拒绝，本类型只如实记录）
 * @param sha256     内容 SHA-256 摘要（64 位小写十六进制，对应 {@code attachment.sha256} 必填列）
 */
public record StagedAttachment(String storageKey, long sizeBytes, String sha256) {
}