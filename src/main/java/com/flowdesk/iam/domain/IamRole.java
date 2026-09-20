package com.flowdesk.iam.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Predefined role persisted in {@code iam_role}.
 */
@Data
@TableName("iam_role")
public class IamRole {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
}
