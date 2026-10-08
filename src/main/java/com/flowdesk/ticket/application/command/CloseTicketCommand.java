package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人手动关闭工单（IT 异常关闭，`docs/kickoff.md` 4.8）。
 *
 * <p>只有「处理中」的当前负责人可以关闭；必须选择标准关闭原因并填写具体说明。
 * 选择「重复工单」时必须关联另一张有效工单，其他原因禁止传该字段（跨字段规则在服务层判定）。</p>
 *
 * <p>{@code reasonCode} 刻意**不包含** {@code REQUESTER_NO_RESPONSE}：那是员工逾期未补充时的
 * 系统自动关闭（{@code close_method = AUTO_SUPPLEMENT_TIMEOUT}，操作人记为系统），
 * 属于 backlog 第 3 项，不走这个人工接口。</p>
 */
public record CloseTicketCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "关闭原因不能为空")
        @Pattern(
                regexp = "DUPLICATE|OUT_OF_SCOPE|INVALID",
                message = "关闭原因必须是DUPLICATE、OUT_OF_SCOPE或INVALID")
        String reasonCode,

        @NotBlank(message = "关闭说明不能为空")
        @Size(max = 1000, message = "关闭说明最长 1000 个字符")
        String description,

        @Size(max = 32, message = "重复工单编号最长 32 个字符")
        String duplicateTicketNo) {

    public CloseTicketCommand {
        description = description == null ? null : description.strip();
        // 去空白后为空串等于没传：非重复原因的「禁止传该字段」不必为一个空串报错
        duplicateTicketNo =
                duplicateTicketNo == null ? null : duplicateTicketNo.strip();
        duplicateTicketNo =
                duplicateTicketNo == null || duplicateTicketNo.isEmpty()
                        ? null
                        : duplicateTicketNo;
    }
}
