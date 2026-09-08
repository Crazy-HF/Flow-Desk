package com.flowdesk.iam.domain.bo;

import com.flowdesk.iam.domain.IamUserStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 用户列表与详情可安全返回的身份信息，不包含密码摘要。 */
@Data
public class IamUserBO {

    private Long id;
    private String username;
    private String displayName;
    private IamUserStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
