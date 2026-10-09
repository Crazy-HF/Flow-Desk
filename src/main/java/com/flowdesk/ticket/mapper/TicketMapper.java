package com.flowdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.infrastructure.persistence.TicketAssigneeRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDuplicateTargetRow;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /** 按用户与规范化提交键读取不可变创建结果，不查询其他用户的提交。 */
    @Select("""
            SELECT id, ticket_no, created_at
            FROM ticket
            WHERE requester_id = #{requesterId}
              AND submission_key = #{submissionKey}
            """)
    Ticket selectCreationByRequesterAndSubmissionKey(
            @Param("requesterId") long requesterId,
            @Param("submissionKey") String submissionKey);

    /** 权限与资源关系同时成立才能读取正文；不返回提交键。 */
    @Select("""
            SELECT t.id, t.ticket_no, t.title, t.description,
                   t.category_id, c.name AS category_name,
                   t.priority, t.status,
                   t.requester_id, requester.display_name AS requester_display_name,
                   t.assignee_id, assignee.display_name AS assignee_display_name,
                   t.action_deadline_at, t.version,
                   t.cancel_requested_at, t.cancel_request_reason, t.cancel_request_deadline_at,
                   t.completion_method, t.close_method, t.close_reason, t.ended_at,
                   t.created_at, t.updated_at
            FROM ticket t
            JOIN ticket_category c ON c.id = t.category_id
            JOIN iam_user requester ON requester.id = t.requester_id
            LEFT JOIN iam_user assignee ON assignee.id = t.assignee_id
            WHERE t.ticket_no = #{ticketNo}
              AND (
                (#{canViewOwn} = TRUE AND t.requester_id = #{currentUserId})
                OR (#{canViewQueue} = TRUE AND t.status = 'PENDING')
                OR (#{canViewParticipated} = TRUE AND (
                    t.assignee_id = #{currentUserId}
                    OR EXISTS (
                        SELECT 1 FROM ticket_participant p
                        WHERE p.ticket_id = t.id AND p.user_id = #{currentUserId}
                    )
                ))
              )
            """)
    TicketDetailRow selectVisibleDetail(
            @Param("ticketNo") String ticketNo,
            @Param("currentUserId") long currentUserId,
            @Param("canViewOwn") boolean canViewOwn,
            @Param("canViewQueue") boolean canViewQueue,
            @Param("canViewParticipated") boolean canViewParticipated);

    /** 数据范围由 scope 决定，用户 ID 仅由应用服务的认证上下文提供。 */
    @Select("""
            <script>
            SELECT t.ticket_no, t.title,
                   t.category_id, c.name AS category_name,
                   t.priority, t.status,
                   t.requester_id, requester.display_name AS requester_display_name,
                   t.assignee_id, assignee.display_name AS assignee_display_name,
                   t.action_deadline_at, t.created_at, t.updated_at, t.version
            FROM ticket t
            JOIN ticket_category c ON c.id = t.category_id
            JOIN iam_user requester ON requester.id = t.requester_id
            LEFT JOIN iam_user assignee ON assignee.id = t.assignee_id
            <choose>
              <when test="query.scope != null and query.scope.name() == 'REQUESTED_BY_ME'">
                WHERE t.requester_id = #{currentUserId}
              </when>
              <when test="query.scope != null and query.scope.name() == 'PENDING_QUEUE'">
                WHERE t.status = 'PENDING'
              </when>
              <when test="query.scope != null and query.scope.name() == 'ASSIGNED_TO_ME'">
                WHERE t.assignee_id = #{currentUserId}
              </when>
              <when test="query.scope != null and query.scope.name() == 'PARTICIPATED_BY_ME'">
                WHERE EXISTS (
                  SELECT 1 FROM ticket_participant p
                  WHERE p.ticket_id = t.id AND p.user_id = #{currentUserId}
                )
              </when>
              <otherwise>
                WHERE 1 = 0
              </otherwise>
            </choose>
            <if test="query.status != null and !query.status.isEmpty()">
              AND t.status IN
              <foreach collection="query.status" item="value"
                       open="(" separator="," close=")">
                #{value}
              </foreach>
            </if>
            <if test="query.priority != null and !query.priority.isEmpty()">
              AND t.priority IN
              <foreach collection="query.priority" item="value"
                       open="(" separator="," close=")">
                #{value}
              </foreach>
            </if>
            <if test="query.categoryId != null">
              AND t.category_id = #{query.categoryId}
            </if>
            <if test="keywordPattern != null">
              AND (t.ticket_no LIKE #{keywordPattern} ESCAPE '!'
                   OR t.title LIKE #{keywordPattern} ESCAPE '!')
            </if>
            <if test="createdFrom != null">
              AND t.created_at &gt;= #{createdFrom}
            </if>
            <if test="createdTo != null">
              AND t.created_at &lt;= #{createdTo}
            </if>
            <choose>
              <when test="query.sort.name() == 'CREATED_DESC'">
                ORDER BY t.created_at DESC, t.id DESC
              </when>
              <when test="query.sort.name() == 'CREATED_ASC'">
                ORDER BY t.created_at ASC, t.id ASC
              </when>
              <when test="query.sort.name() == 'PRIORITY_DESC_CREATED_ASC'">
                ORDER BY CASE t.priority
                    WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC,
                    t.created_at ASC, t.id ASC
              </when>
              <otherwise>
                ORDER BY t.updated_at DESC, t.id DESC
              </otherwise>
            </choose>
            </script>
            """)
    Page<TicketListRow> selectScopedPage(
            Page<TicketListRow> page,
            @Param("currentUserId") long currentUserId,
            @Param("query") TicketQuery query,
            @Param("keywordPattern") String keywordPattern,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    /** 领取待处理工单。 */
    @Update("""
        UPDATE ticket
        SET status = 'PROCESSING',
            assignee_id = #{assigneeId},
            version = version + 1,
            record_seq = record_seq + 1,
            updated_at = #{now}
        WHERE id = #{ticketId}
          AND version = #{expectedVersion}
          AND status = 'PENDING'
          AND assignee_id IS NULL
          AND requester_id <> #{assigneeId}
        """)
    int claimPending(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("assigneeId") long assigneeId,
            @Param("now") LocalDateTime now);

    /** 条件更新失败后读取最新状态；锁定读避免事务快照返回旧版本。 */
    @Select("""
            SELECT id, status, version
            FROM ticket
            WHERE id = #{ticketId}
            FOR UPDATE
            """)
    Ticket selectClaimConflictSnapshotForUpdate(@Param("ticketId") long ticketId);


    /** 当前负责人在 PROCESSING 上追加处理记录：状态与负责人不变，只递增版本与记录序号。 */
    @Update("""
    UPDATE ticket
    SET version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int advanceAssigneeAction(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /** 当前负责人提交解决结果：进入待确认并写入确认期限。 */
    @Update("""
    UPDATE ticket
    SET status = 'WAITING_FOR_CONFIRMATION',
        action_deadline_at = #{deadlineAt},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int submitResolution(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("deadlineAt") LocalDateTime deadlineAt,
            @Param("now") LocalDateTime now);

    /** 提交人确认解决：进入终态，期限失效并记录结束时间与完成方式。 */
    @Update("""
    UPDATE ticket
    SET status = 'COMPLETED',
        action_deadline_at = NULL,
        completion_method = 'REQUESTER_CONFIRMED',
        cancel_requested_at = NULL,
        cancel_request_reason = NULL,
        cancel_request_deadline_at = NULL,
        ended_at = #{now},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'WAITING_FOR_CONFIRMATION'
      AND requester_id = #{actorId}
    """)
    int confirmResolution(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 当前负责人撤回补充请求：回到处理中，并让原补充期限失效。
     *
     * <p>与 {@link #reportUnresolved} 形状相同、只有状态与身份列不同。刻意写成两条独立的 SQL，
     * 而不是把状态与身份列参数化：条件更新里的"预期状态 + 预期负责人"是判定的一部分，
     * 参数化会让"谁能推进哪一步"从 SQL 里消失。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'PROCESSING',
        action_deadline_at = NULL,
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'WAITING_FOR_REQUESTER'
      AND assignee_id = #{actorId}
    """)
    int withdrawSupplementRequest(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 提交人反馈问题未解决：回到处理中，并让原确认期限失效；负责人保留。
     *
     * <p>只改状态、期限、版本与记录序号——`assignee_id` 不动，因为退回不等于换人
     * （`docs/kickoff.md` 4.5：原负责人继续排查）。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'PROCESSING',
        action_deadline_at = NULL,
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'WAITING_FOR_CONFIRMATION'
      AND requester_id = #{actorId}
    """)
    int reportUnresolved(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 当前负责人请求员工补充信息：进入待补充并写入补充期限。
     *
     * <p>状态与期限必须**在同一条 UPDATE 里原子写入**：{@code ck_ticket_status_deadline} 要求
     * {@code WAITING_FOR_REQUESTER} 必须有期限、其他状态必须为空，拆成两次更新会在中间撞约束。</p>
     *
     * <p><strong>分工</strong>：本条由用户写入（2026-10-06 决定后续动作的 Mapper 由用户编写）。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'WAITING_FOR_REQUESTER',
        action_deadline_at = #{deadlineAt},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int requestSupplement(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("deadlineAt") LocalDateTime deadlineAt,
            @Param("now") LocalDateTime now);

    /**
     * 提交人补充信息：回到处理中，并让原补充期限失效；负责人保留。
     *
     * <p>与 {@link #withdrawSupplementRequest} 形状相同、只有状态与身份列不同。刻意写成两条
     * 独立的 SQL，而不是把状态与身份列参数化：条件更新里的"预期状态 + 预期提交人/负责人"
     * 是判定的一部分，参数化会让"谁能推进哪一步"从 SQL 里消失。</p>
     *
     * <p><strong>分工</strong>：本条由用户写入（2026-10-06 决定后续动作的 Mapper 由用户编写）。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'PROCESSING',
        action_deadline_at = NULL,
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'WAITING_FOR_REQUESTER'
      AND requester_id = #{actorId}
    """)
    int supplement(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 查询转交候选人
     */
    @Select("""
            SELECT u.id AS id, u.display_name AS displayName
            FROM iam_user u
            JOIN iam_user_role ur ON ur.user_id = u.id
            JOIN iam_role r ON r.id = ur.role_id
            WHERE r.code = 'IT_SUPPORT'
              AND u.status = 'ENABLED'
              AND u.id <> #{requesterId}
              AND u.id <> #{currentAssigneeId}
            ORDER BY u.id
            """)
    List<TicketAssigneeRow> selectTransferCandidates(
            @Param("requesterId") long requesterId,
            @Param("currentAssigneeId") long currentAssigneeId);

    /**当前负责人调整工单分类*/
    @Update("""
    UPDATE ticket
    SET category_id = #{categoryId},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER')
      AND assignee_id = #{actorId}
    """)
    int changeCategory(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("categoryId") long categoryId,
            @Param("now") LocalDateTime now);

    /** 当前负责人调整工单优先级：与 {@link #changeCategory} 形状相同，只有被替换的列不同。 */
    @Update("""
    UPDATE ticket
    SET priority = #{priority},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER')
      AND assignee_id = #{actorId}
    """)
    int changePriority(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("priority") String priority,
            @Param("now") LocalDateTime now);

    /**当前负责人把工单直接转交给另一名 IT 用户：原子替换负责人，状态与期限不变。*/
    @Update("""
    UPDATE ticket
    SET assignee_id = #{newAssigneeId},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER')
      AND assignee_id = #{actorId}
      AND requester_id <> #{newAssigneeId}
    """)
    int transfer(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("newAssigneeId") long newAssigneeId,
            @Param("now") LocalDateTime now);

    /**
     * 当前负责人手动关闭工单：只有「处理中」可以关闭，待受理必须先领取。
     *
     * <p>关闭方式固定 {@code MANUAL}，关闭原因由调用方保证是 {@code DUPLICATE} /
     * {@code OUT_OF_SCOPE} / {@code INVALID} 之一——{@code ck_ticket_close_semantics} 把
     * {@code AUTO_SUPPLEMENT_TIMEOUT} + {@code REQUESTER_NO_RESPONSE} 这条自动关闭路径
     * 留给 backlog 第 3 项，这个接口进不去。</p>
     *
     * <p>状态、关闭字段与 {@code ended_at} 写在同一条 UPDATE 内：{@code ck_ticket_status_ended}
     * 要求终态必须有结束时间，拆开就会撞约束。负责人不写，关闭后保留为历史信息；
     * 「处理中」的期限本来就是空，{@code ck_ticket_status_deadline} 无需额外处理。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'CLOSED',
        close_method = 'MANUAL',
        close_reason = #{closeReason},
        cancel_requested_at = NULL,
        cancel_request_reason = NULL,
        cancel_request_deadline_at = NULL,
        ended_at = #{now},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND status = 'PROCESSING'
      AND assignee_id = #{actorId}
    """)
    int closeManually(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("closeReason") String closeReason,
            @Param("now") LocalDateTime now);

    /**
     * 提交人直接撤销自己的工单：**只剩「待受理」**（2026-10-08 规则变更）。
     *
     * <p>待受理没有负责人，没有人需要批准；处理中、待补充、待确认改走
     * {@link #requestCancel} + {@link #approveCancel} / {@link #rejectCancel} 两阶段。</p>
     *
     * <p>状态与 {@code action_deadline_at} 必须**在同一条 UPDATE 里原子写入**：
     * {@code ck_ticket_status_deadline} 要求待补充与待确认之外的状态期限为空。
     * 待受理本来期限就是空，这条在这里是防御性的——收窄之后已经没有带期限的来源状态，
     * 但保留它可以让"将来放宽允许状态"的人不必重新推一遍约束。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'CANCELED',
        action_deadline_at = NULL,
        ended_at = #{now},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND requester_id = #{actorId}
      AND status = 'PENDING'
    """)
    int cancel(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 关闭为「重复工单」时解析目标工单。
     *
     * <p>口径（{@code docs/kickoff.md} 4.8 与 {@code docs/api-design.md} 6.3）：目标必须存在、
     * 属于**同一提交人**、不是自身，且状态不是 {@code CANCELED} / {@code CLOSED}——
     * 已完成与仍在流转的工单都可以作为重复目标，重复只说明"同一问题已有另一张单"。</p>
     *
     * <p>「同一提交人」写进 WHERE 而不是查出来再判：目标属于别人与目标不存在都得到
     * {@code null}，调用方统一按 400 处理，不向调用方回显他人的工单是否存在。</p>
     */
    @Select("""
    SELECT t.id AS id,
           t.ticket_no AS ticketNo,
           t.status AS status
    FROM ticket t
    WHERE t.ticket_no = #{duplicateTicketNo}
      AND t.requester_id = #{requesterId}
      AND t.id <> #{sourceTicketId}
      AND t.status NOT IN ('CANCELED', 'CLOSED')
    """)
    TicketDuplicateTargetRow selectDuplicateTarget(
            @Param("sourceTicketId") long sourceTicketId,
            @Param("requesterId") long requesterId,
            @Param("duplicateTicketNo") String duplicateTicketNo);

    /**
     * 提交人发起撤销请求：三个「有人负责且未终结」的状态都可以发起，状态不变。
     *
     * <p>{@code cancel_requested_at IS NULL} 是判定的一部分：一张工单同时只能有一个待决请求。
     * 重复发起由条件更新挡成 409，而不是静默覆盖前一次的说明。</p>
     *
     * <p>三列必须**在同一条 UPDATE 里**写入：{@code ck_ticket_cancel_request_pair} 要求三列同生同灭。
     * 状态不在 SET 里，因此不必动 {@code action_deadline_at}，也就不会撞期限约束——
     * 这一点与 {@link #cancel} 恰好相反（那条必须清期限）。</p>
     */
    @Update("""
    UPDATE ticket
    SET cancel_requested_at = #{now},
        cancel_request_reason = #{reason},
        cancel_request_deadline_at = #{deadlineAt},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND requester_id = #{actorId}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NULL
    """)
    int requestCancel(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("reason") String reason,
            @Param("deadlineAt") LocalDateTime deadlineAt,
            @Param("now") LocalDateTime now);

    /**
     * 当前负责人批准撤销请求：进入终态「已取消」。
     *
     * <p><b>必须同时清 {@code action_deadline_at}</b>：待补充与待确认这两个来源状态本身带着期限，
     * 而 {@code ck_ticket_status_deadline} 要求其它状态期限为空；只改 status 会直接撞约束。</p>
     *
     * <p>请求三列的清空与 {@code ended_at} 也在同一条里：
     * {@code ck_ticket_cancel_request_status} 不允许待决请求留在终态上，
     * {@code ck_ticket_status_ended} 又要求终态必须有结束时间。</p>
     *
     * <p>{@code cancel_requested_at IS NOT NULL} 与版本一起进 WHERE：批准、拒绝、提交人撤回
     * 三者并发时只有一条能命中，另外两路拿到 409 而不是互相覆盖。</p>
     */
    @Update("""
    UPDATE ticket
    SET status = 'CANCELED',
        action_deadline_at = NULL,
        cancel_requested_at = NULL,
        cancel_request_reason = NULL,
        cancel_request_deadline_at = NULL,
        ended_at = #{now},
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND assignee_id = #{actorId}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NOT NULL
    """)
    int approveCancel(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 当前负责人拒绝撤销请求：状态与期限都不变，只让请求失效。
     *
     * <p>与 {@link #approveCancel} 形状相同、只有状态迁移那一行不同。刻意写成两条独立的 SQL，
     * 与 {@link #withdrawSupplementRequest} / {@link #supplement} 同一理由：
     * "批准还是拒绝"是业务判定本身，参数化会让它从 SQL 里消失。</p>
     */
    @Update("""
    UPDATE ticket
    SET cancel_requested_at = NULL,
        cancel_request_reason = NULL,
        cancel_request_deadline_at = NULL,
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND assignee_id = #{actorId}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NOT NULL
    """)
    int rejectCancel(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);

    /**
     * 提交人撤回自己的撤销请求：状态与期限都不变，只让请求失效。
     *
     * <p>身份列是 {@code requester_id} 而不是 {@code assignee_id}：这是提交人的动作，
     * 当前负责人即使同时持有提交人权限也撤不掉别人的请求（与 {@link #cancel} 同一条口径）。</p>
     */
    @Update("""
    UPDATE ticket
    SET cancel_requested_at = NULL,
        cancel_request_reason = NULL,
        cancel_request_deadline_at = NULL,
        version = version + 1,
        record_seq = record_seq + 1,
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND requester_id = #{actorId}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NOT NULL
    """)
    int withdrawCancelRequest(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);
}
