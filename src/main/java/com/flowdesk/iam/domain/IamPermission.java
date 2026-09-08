package com.flowdesk.iam.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Stable backend capability persisted in {@code iam_permission}.
 */
@Data
@TableName("iam_permission")
public class IamPermission {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
}
