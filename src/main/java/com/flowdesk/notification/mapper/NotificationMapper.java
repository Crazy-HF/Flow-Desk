package com.flowdesk.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.notification.domain.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信表映射（项目无 {@code @MapperScan}，靠 {@code @Mapper} 注解注册）。
 *
 * <p>面向用户的三条读写（列表、未读计数、标记已读）条件都带 {@code recipient_id}——
 * 「用户只能读写本人的通知」是这一层与 ServiceImpl 共同守住的边界；
 * 发布与提醒回收是系统侧入口，由业务动作在自身事务内调用，不经过认证上下文。</p>
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /** 本人通知列表，按 id 倒序（id 单调递增，等价于按创建时间倒序）。 */
    @Select("""
            SELECT id, recipient_id AS recipientId, category, title, body,
                   ticket_id AS ticketId, ticket_no AS ticketNo, dedup_key AS dedupKey,
                   read_at AS readAt, created_at AS createdAt
            FROM notification
            WHERE recipient_id = #{recipientId}
            ORDER BY id DESC
            """)
    Page<Notification> selectRecipientPage(Page<Notification> page, @Param("recipientId") long recipientId);

    /** 本人未读通知列表。 */
    @Select("""
            SELECT id, recipient_id AS recipientId, category, title, body,
                   ticket_id AS ticketId, ticket_no AS ticketNo, dedup_key AS dedupKey,
                   read_at AS readAt, created_at AS createdAt
            FROM notification
            WHERE recipient_id = #{recipientId}
              AND read_at IS NULL
            ORDER BY id DESC
            """)
    Page<Notification> selectUnreadRecipientPage(Page<Notification> page, @Param("recipientId") long recipientId);

    /** 本人未读条数（顶栏徽标）。 */
    @Select("""
            SELECT COUNT(*) FROM notification
            WHERE recipient_id = #{recipientId}
              AND read_at IS NULL
            """)
    long countUnread(@Param("recipientId") long recipientId);

    /** 通知是否存在且属于本人；返回 id 表示存在，null 表示不存在或非本人（统一按 404 处理）。 */
    @Select("""
            SELECT id FROM notification
            WHERE id = #{notificationId}
              AND recipient_id = #{recipientId}
            """)
    Long selectOwnedId(@Param("notificationId") long notificationId, @Param("recipientId") long recipientId);

    /**
     * 幂等写入：带 {@code dedup_key} 的通知重复发布时命中唯一键
     * {@code uk_notification_recipient_dedup(recipient_id, dedup_key)}，
     * 走 {@code ON DUPLICATE KEY UPDATE} 静默跳过——不报错、不新增行、不改动原行。
     *
     * <p>为什么不是 {@code INSERT IGNORE}：它会把所有可忽略错误一起吞掉（超长截断、非法日期……），
     * 唯一键冲突和真实数据问题就再也分不出来；{@code ON DUPLICATE KEY UPDATE} 只处理唯一键冲突，
     * 其它错误照常抛出。</p>
     *
     * <p>为什么更新成 {@code id = id}：等值赋值不改变任何列，冲突时退化为一次空更新，
     * 原行的 {@code read_at} 与 {@code created_at} 原样保留——重复扫描不该把已读的提醒顶回未读，
     * 也不该刷新创建时间。</p>
     *
     * <p><b>返回值不能用来判断「是否真的插入了」</b>：Connector/J 默认 {@code useAffectedRows=false}
     * （协议层打开 CLIENT_FOUND_ROWS），重复键时受影响行数是 1 而不是 0，
     * 用 {@code == 1} 判插入成功会把重复发布误判成新插入。调用方只看有没有抛异常。</p>
     *
     * <p>调用方只在 {@code dedup_key} 非空时走这里；{@code dedup_key} 为空时唯一键不生效
     * （MySQL 允许多个 NULL），本方法会退化成一次普通插入。</p>
     */
    @Insert("""
            INSERT INTO notification (recipient_id, category, title, body,
                                      ticket_id, ticket_no, dedup_key, read_at, created_at)
            VALUES (#{recipientId}, #{category}, #{title}, #{body},
                    #{ticketId}, #{ticketNo}, #{dedupKey}, NULL, #{createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(Notification notification);

    /**
     * 按工单回收提醒：删除该工单下、属于给定类别、且带 {@code dedup_key} 的通知。
     *
     * <p>三个条件各管一件事：{@code ticket_id} 把影响面锁死在一张工单上；
     * {@code category IN (...)} 只认调用方点名的提醒类别；{@code dedup_key IS NOT NULL}
     * 给类别参数兜底——业务事件通知发布时 {@code dedup_key} 为空，传错类别也删不到它们。</p>
     *
     * <p>不看 {@code read_at}：提醒是「这段时间快到了」的临时提示，不是业务事实。
     * 期限一作废，它描述的事就不成立，已读同样要删；要查业务事实去 {@code ticket_record} 时间线。</p>
     *
     * <p>走 {@code fk_notification_ticket}（InnoDB 为外键列自动建的索引），不新增索引。</p>
     */
    @Delete("""
            <script>
            DELETE FROM notification
            WHERE ticket_id = #{ticketId}
              AND dedup_key IS NOT NULL
              AND category IN
            <foreach collection="categories"
                     item="category"
                     open="("
                     separator=","
                     close=")">
                #{category}
            </foreach>
            </script>
            """)
    int deleteRemindersByTicket(@Param("ticketId") long ticketId,
                                @Param("categories") List<String> categories);

    /** 标记本人某条通知已读；已读时影响行数为 0（幂等），非本人或不存在同样为 0。 */
    @Update("""
            UPDATE notification SET read_at = #{now}
            WHERE id = #{notificationId}
              AND recipient_id = #{recipientId}
              AND read_at IS NULL
            """)
    int markRead(@Param("notificationId") long notificationId,
            @Param("recipientId") long recipientId,
            @Param("now") LocalDateTime now);

    /** 标记本人全部未读通知已读；没有未读时影响行数为 0（幂等）。 */
    @Update("""
            UPDATE notification SET read_at = #{now}
            WHERE recipient_id = #{recipientId}
              AND read_at IS NULL
            """)
    int markAllRead(@Param("recipientId") long recipientId, @Param("now") LocalDateTime now);

    /**
     * 清理任务第一段：删除已读时间早于阈值的通知，单批上限 {@code limit} 行，供调用方循环至清空。
     * 未读通知不由这一段删除，另有 {@link #deleteUnreadBefore}（按 {@code created_at} 计）。
     */
    @Delete("""
            DELETE FROM notification
            WHERE read_at IS NOT NULL
              AND read_at < #{cutoff}
            LIMIT #{limit}
            """)
    int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 清理任务第二段：删除创建时间早于阈值的**未读**通知，单批上限 {@code limit} 行，
     * 供调用方循环至清空。
     *
     * <p>删除会释放 {@code uk_notification_recipient_dedup} 上的键位：若被删掉的提醒在删除时期限还没到，
     * 下一轮扫描会补发一条同 key 的提醒——那不是重复发布，是旧的那条已经不存在了。
     * 提前量不超过未读保留期时不会发生，见 {@code NotificationProperties} 类注释。</p>
     */
    @Delete("""
            DELETE FROM notification
            WHERE read_at IS NULL
              AND created_at < #{cutoff}
            LIMIT #{limit}
            """)
    int deleteUnreadBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
