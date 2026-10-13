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

    /**
     * 按工单 id 复用同一条可见性判定（附件下载走这里，确保与详情权限口径完全一致；
     * 分支谓词逐字复制自 {@link #selectVisibleDetail}）。
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM ticket t
                WHERE t.id = #{ticketId}
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
            )
            """)
    boolean existsVisibleTicketById(
            @Param("ticketId") long ticketId,
            @Param("currentUserId") long currentUserId,
            @Param("canViewOwn") boolean canViewOwn,
            @Param("canViewQueue") boolean canViewQueue,
            @Param("canViewParticipated") boolean canViewParticipated);

    /**
     * 数据范围由 scope 决定，用户 ID 仅由应用服务的认证上下文提供。
     *
     * <p><b>「待我批准」筛选</b>（{@code docs/api-design.md} 5.3、完整版 backlog 第 7 项）：只在
     * {@code ASSIGNED_TO_ME} 下生效，语义是「本人是当前负责人 且 有**未过期**的待决撤销请求」。
     * 状态白名单只留「处理中」与「待确认」——「待补充期间不裁决」是叠加在它之上的既有条件，因此
     * 处于待补充的工单**不出现在**这个筛选里，员工补充完、工单回到处理中后自动恢复。这与详情里的
     * {@code approve-cancel} / {@code reject-cancel} 两格是同一个判定，两边必须同时成立或同时不成立
     * （{@code TicketQueryServiceIT} 有交叉验证用例）。</p>
     *
     * <p>{@code now} 只被这个筛选用；它来自应用注入的 UTC 时钟，与四条撤销条件更新的 {@code #{now}}
     * 和详情里的 {@code expired} 判定同源。</p>
     */
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
                <if test="query.awaitingMyApproval != null and query.awaitingMyApproval">
                  AND t.cancel_requested_at IS NOT NULL
                  AND t.cancel_request_deadline_at &gt; #{now}
                  AND t.status IN ('PROCESSING', 'WAITING_FOR_CONFIRMATION')
                </if>
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
            @Param("createdTo") LocalDateTime createdTo,
            @Param("now") LocalDateTime now);

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
     * <p>「一张工单同时只能有一个**有效**请求」由条件更新保证：已有未过期的请求时重复发起被挡成 409，
     * 而不是静默覆盖前一次的说明；请求一旦过期即可被新请求直接覆盖（2026-10-10 裁决）。</p>
     *
     * <p>三列必须**在同一条 UPDATE 里**写入：{@code ck_ticket_cancel_request_pair} 要求三列同生同灭。
     * 状态不在 SET 里，因此不必动 {@code action_deadline_at}，也就不会撞期限约束——
     * 这一点与 {@link #cancel} 恰好相反（那条必须清期限）。</p>
     *
     * <p><b>2026-10-10 裁决，本版已实现</b>：{@code cancel_requested_at IS NULL} 放宽为「没有**未过期**的
     * 请求」——已过期的请求由新请求直接替换、不再返回 409，且不设终身次数上限（同一时刻仍只允许一个
     * 有效请求）。覆盖发生时服务层先补写一条 {@code CANCELLATION_REQUEST_EXPIRED} 记录，
     * 见 {@code docs/implementation-plan.md} 9.3 待改清单 ①～③。</p>
     *
     * <p><b>{@code recordSeqIncrement} 为什么由调用方给</b>：覆盖已过期的请求时，这一次动作要写两条
     * 记录（先「已过期」留痕、再新的 {@code CANCELLATION_REQUEST}），序号必须推进 2；没有待决请求时
     * 只写一条，推进 1。调用方在读到快照时就已经知道是哪种情况，直接传值比在 SQL 里用
     * {@code CASE WHEN cancel_request_deadline_at <= #{now}} 更清楚——后者依赖 {@code SET} 子句的
     * 求值顺序（MySQL 的赋值从左到右、且后一个能看到前一个的结果），一旦有人调整列顺序就会静默算错。
     * 覆盖与否则仍由 {@code WHERE} 裁决：快照与提交时不一致时这条更新命中 0 行，调用方拿到 409。</p>
     */
    @Update("""
    UPDATE ticket
    SET cancel_requested_at = #{now},
        cancel_request_reason = #{reason},
        cancel_request_deadline_at = #{deadlineAt},
        version = version + 1,
        record_seq = record_seq + #{recordSeqIncrement},
        updated_at = #{now}
    WHERE id = #{ticketId}
      AND version = #{expectedVersion}
      AND requester_id = #{actorId}
      AND status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
      AND (cancel_requested_at IS NULL OR cancel_request_deadline_at <= #{now})
    """)
    int requestCancel(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("reason") String reason,
            @Param("deadlineAt") LocalDateTime deadlineAt,
            @Param("recordSeqIncrement") int recordSeqIncrement,
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
     *
     * <p><b>状态白名单不含「待补充」</b>（2026-10-10 用户裁决）：负责人刚把球交给员工，
     * 不允许在信息不全的时候终止整张工单——批准与拒绝只在「处理中」与「待确认」开放。
     * 请求本身仍合法地留在工单上（{@code ck_ticket_cancel_request_status} 允许它停在待补充），
     * 员工补充完、工单回到「处理中」即可裁决，响应期限不重算。</p>
     *
     * <p><b>请求未过期也在 {@code WHERE} 里</b>（2026-10-10 裁决，本版已实现）：期限届满后批准、拒绝、
     * 撤回三条路一起关闭，调用方统一拿到 409 + 最新快照（刷新详情即可看到「已过期」）。
     * {@link #rejectCancel} 与 {@link #withdrawCancelRequest} 同样带这一条。</p>
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
      AND status IN ('PROCESSING', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NOT NULL
      AND cancel_request_deadline_at > #{now}
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
     *
     * <p>状态白名单与 {@link #approveCancel} 一样不含「待补充」（2026-10-10 用户裁决）：
     * 待补充期间不裁决，员工补充完、工单回到「处理中」再拒绝。</p>
     *
     * <p>与 {@link #approveCancel} 一样带「请求未过期」：期限届满后连「拒绝」也不再允许——否则提交人
     * 会以为申请被正式驳回，而实际上它已经失效（2026-10-10 裁决，本版已实现）。</p>
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
      AND status IN ('PROCESSING', 'WAITING_FOR_CONFIRMATION')
      AND cancel_requested_at IS NOT NULL
      AND cancel_request_deadline_at > #{now}
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
     *
     * <p>与批准 / 拒绝一样带「请求未过期」（2026-10-10 裁决，本版已实现）：三个动作一起关闭，因此
     * <b>不存在「先撤回再重新发起」这条路径</b>——过期之后提交人直接重新发起即可，覆盖由
     * {@link #requestCancel} 的条件承担。</p>
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
      AND cancel_request_deadline_at > #{now}
    """)
    int withdrawCancelRequest(
            @Param("ticketId") long ticketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") LocalDateTime now);
}
