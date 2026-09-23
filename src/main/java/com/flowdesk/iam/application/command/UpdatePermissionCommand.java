package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePermissionCommand(

        @NotBlank(message = "权限名称不能为空")
        @Size(max = 100, message = "权限名称最大长度为100")
        String name,

        @Size(max = 255, message = "权限描述最大长度为255")
        String description
) {
}
