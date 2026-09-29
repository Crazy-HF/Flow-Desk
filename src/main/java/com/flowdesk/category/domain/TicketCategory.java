package com.flowdesk.category.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工单分类，对应 ticket_category 表。
 */
@Data
@TableName("ticket_category")
public class TicketCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long version;
}