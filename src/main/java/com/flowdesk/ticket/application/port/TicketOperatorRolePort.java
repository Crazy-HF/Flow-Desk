package com.flowdesk.ticket.application.port;

/**
 * 概览授权所需的 IT 身份核验（{@code docs/api-design.md} 8.5）。
 *
 * <p>数据概览要求「当前持有 {@code IT_SUPPORT}」。权限码可以由任意角色授予，而角色归属只存在于
 * IAM 侧的可信数据里：{@code AuthPrincipal} 只有用户标识与会话信息，前端传来的角色标签更不是证据。
 * 因此这一层必须查库，不能从认证对象的授权集合推断。</p>
 *
 * <p><strong>为什么不复用 {@code TicketClaimantPort.isEligibleClaimant}</strong>：它的实现今天恰好
 * 也是"启用账号 + IT_SUPPORT"，但语义是"能不能领取工单"。领取资格一旦收紧（例如排除某些 IT），
 * 概览授权会跟着被悄悄改掉；两个不同的问题共用一个判定，出问题时也看不出是谁该改。</p>
 */
public interface TicketOperatorRolePort {

    /** 当前仍启用且持有 {@code IT_SUPPORT} 角色时为 {@code true}。 */
    boolean hasActiveItSupportRole(long userId);
}
