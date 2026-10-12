package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTicketCommand(
        @NotBlank
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}$",
                message = "提交键必须是 UUID")
        String submissionKey,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多200个字符")
        String title,

        @NotBlank(message = "描述不能为空")
        @Size(max = 10000, message = "描述最多10000个字符")
        String description,

        @NotNull(message = "分类不能为空")
        @Positive(message = "分类ID必须为正数")
        Long categoryId,

        @NotBlank(message = "优先级不能为空")
        @Pattern(
                regexp = "LOW|MEDIUM|HIGH",
                message = "优先级必须是LOW、MEDIUM或HIGH")
        String priority,

        /**
         * 可选：新工单引用的原工单（`docs/kickoff.md` 4.10、`docs/api-design.md` 6.2）。
         * 必须是当前提交人有权查看的工单，由服务层按详情同一条可见性 SQL 校验；
         * 校验失败统一 `400/VALIDATION_FAILED`，不向调用方回显他人工单是否存在。
         */
        @Size(max = 32, message = "原工单编号最长 32 个字符")
        String sourceTicketNo
) {
    public CreateTicketCommand {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
        // 去空白后为空串等于没传：可选的原工单引用不必为一个空串报错
        sourceTicketNo = sourceTicketNo == null ? null : sourceTicketNo.strip();
        sourceTicketNo = sourceTicketNo == null || sourceTicketNo.isEmpty()
                ? null
                : sourceTicketNo;
    }
}