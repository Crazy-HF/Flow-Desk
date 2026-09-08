package com.flowdesk.iam.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Login identity persisted in {@code iam_user}. The password is an Argon2id hash, never plaintext.
 */
@Data
@TableName("iam_user")
public class IamUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String displayName;
    private String passwordHash;
    private IamUserStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
