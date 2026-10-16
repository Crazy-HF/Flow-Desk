package com.flowdesk.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.notification.domain.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 站内信表映射（项目无 {@code @MapperScan}，靠 {@code @Mapper} 注解注册）。
 *
 * <p>写入与条件更新一律由手写 SQL 表达，条件都带 {@code recipient_id}——
 * 「只能读写本人的通知」是这一层与 ServiceImpl 共同守住的边界。</p>
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
     * 清理任务：删除已读时间早于阈值的通知，单批上限 {@code limit} 行，供调用方循环至清空。
     * 未读通知永远不会被删除。
     */
    @Delete("""
            DELETE FROM notification
            WHERE read_at IS NOT NULL
              AND read_at < #{cutoff}
            LIMIT #{limit}
            """)
    int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
