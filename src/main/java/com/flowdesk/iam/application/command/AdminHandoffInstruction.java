package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 管理性交接方案里的一张工单（{@code docs/api-design.md} 8.3）。
 *
 * <p>只承载「交给谁、按哪个版本交」这三样：编号是服务端重新查询目标用户活动工单集合的键，
 * 版本是每张工单各自的乐观锁，新负责人是接替候选人里选出的用户 ID。</p>
 *
 * <p><strong>为什么字段级校验只做到这里</strong>：工单编号是否存在、是否仍属于目标用户、版本是否仍是最新、
 * 新负责人是否仍是有效候选人，全部取决于数据库当前状态，只能在 {@code AdminHandoffPort} 内加锁后判定，
 * 本记录不做也不应做这些判断。请求自带的缺陷（编号空白、版本为负、新负责人非正）在这里挡成
 * {@code 400/VALIDATION_FAILED}；「同一工单重复出现」由服务层去重时判定，同样属于 {400}。</p>
 */
public record AdminHandoffInstruction(

        @NotBlank(message = "工单编号不能为空")
        @Size(max = 32, message = "工单编号最长 32 个字符")
        String ticketNo,

        @NotNull(message = "工单版本不能为空")
        @PositiveOrZero(message = "工单版本不能为负数")
        Long ticketVersion,

        @NotNull(message = "新负责人不能为空")
        @Positive(message = "新负责人 ID 必须为正数")
        Long newAssigneeId
) {

    public AdminHandoffInstruction {
        ticketNo = ticketNo == null ? null : ticketNo.strip();
    }
}
