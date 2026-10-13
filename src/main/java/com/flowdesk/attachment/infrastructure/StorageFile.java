package com.flowdesk.attachment.infrastructure;

import java.time.Instant;

/**
 * 受控目录中的一个随机存储文件（对账扫描结果）。
 *
 * @param storageKey 32 位小写十六进制存储标识；存储层只返回受控形状的名字（穿越防护的第一道闸）
 * @param modifiedAt 文件最后修改时间（UTC），暂存清理按它判断是否超过保留期
 */
public record StorageFile(String storageKey, Instant modifiedAt) {
}