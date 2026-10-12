package com.flowdesk.ticket.application.result;

/**
 * 详情里的一条工单关联摘要。
 *
 * <p>{@code direction} 相对本工单而言：本单是 {@code source} 记 {@code OUTGOING}，
 * 本单是 {@code target} 记 {@code INCOMING}；{@code relationType} 是 {@code FOLLOW_UP} 或
 * {@code DUPLICATE}。两者组合成固定四格标签（中文文案由界面侧常量维护，后端只返回编码）：</p>
 * <ul>
 *   <li>{@code FOLLOW_UP} + {@code OUTGOING}：本单是后续工单，对端显示为「原工单」；</li>
 *   <li>{@code FOLLOW_UP} + {@code INCOMING}：本单是原工单，对端显示为「后续工单」；</li>
 *   <li>{@code DUPLICATE} + {@code OUTGOING}：本单被关闭为重复，对端显示为「重复于」；</li>
 *   <li>{@code DUPLICATE} + {@code INCOMING}：本单是有效单，对端显示为「被重复」。</li>
 * </ul>
 *
 * <p>摘要只用于展示：它不代表对端工单的详情权限，打开对端时仍执行目标工单自己的
 * 可见性校验（{@code docs/api-design.md} 7.5）。对端正文不进入关联摘要。</p>
 */
public record TicketRelationResult(
        String relationType,
        String direction,
        String ticketNo,
        String title,
        String status) {
}