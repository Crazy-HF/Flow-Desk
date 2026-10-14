package com.flowdesk.attachment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用附件元数据，对应 {@code attachment} 表；文件内容保存在受控本地目录。
 *
 * <p>不携带任何业务归属——业务归属由各自模块的绑定表表达（工单侧是 {@code ticket_attachment}）。</p>
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageKey;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}