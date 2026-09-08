package com.flowdesk.iam.domain.vo;

import com.flowdesk.iam.domain.IamUserStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IamUserVO {

    private Long id;
    private String username;
    private String displayName;
    private IamUserStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
