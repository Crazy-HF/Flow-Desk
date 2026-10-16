package com.flowdesk.notification.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.port.CurrentRecipientPort;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.result.NotificationUnreadCountResult;
import com.flowdesk.notification.application.service.NotificationService;
import com.flowdesk.notification.domain.Notification;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.notification.mapper.NotificationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 站内信用例实现：业务侧发布（同事务）+ 本人范围读取。
 *
 * <p>收件人永远来自认证上下文，不接受调用方传入；越权与不存在统一 404，不暴露存在性。</p>
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final CurrentRecipientPort currentRecipientPort;
    /** 与其它模块同源的 UTC 时钟：写库时间与判定时间只有一个真相来源。 */
    private final Clock clock;

    public NotificationServiceImpl(NotificationMapper notificationMapper,
            CurrentRecipientPort currentRecipientPort,
            Clock clock) {
        this.notificationMapper = notificationMapper;
        this.currentRecipientPort = currentRecipientPort;
        this.clock = clock;
    }

    /**
     *站内信通知发布（带唯一键去重发布）
     */
    @Override
    @Transactional
    public void publish(PublishNotificationCommand command) {
        // 1. 组装通知：正文可为空；dedup_key 只有提醒任务会填，普通业务通知传 null
        Notification notification = new Notification();
        notification.setRecipientId(command.recipientId());
        notification.setCategory(command.category().name());
        notification.setTitle(command.title());
        notification.setBody(command.body());
        notification.setTicketId(command.ticketId());
        notification.setTicketNo(command.ticketNo());
        notification.setDedupKey(command.dedupKey());
        notification.setReadAt(null);
        notification.setCreatedAt(now());

        // 2. 普通通知：每次业务动作都是一条新消息
        if(notification.getDedupKey() == null){
            if (notificationMapper.insert(notification) != 1) {
                throw new IllegalStateException("站内信写入失败");
            }
            return;
        }

        // 3. 带 dedupKey 的通知：重复发布命中唯一键就静默跳过。
        //    这里刻意不看返回值——Connector/J 默认 useAffectedRows=false（协议层开 CLIENT_FOUND_ROWS），
        //    重复键时受影响行数同样是 1，用 == 1 判「插进去了」必然误判；
        //    幂等由 uk_notification_recipient_dedup 保证，调用方只关心有没有抛异常。
        notificationMapper.insertIfAbsent(notification);
    }

    /**
     * 回收站内信提醒：根据 ticketId 和类别批量删除。
     */
    @Override
    @Transactional
    public void recallReminders(long ticketId, List<NotificationCategory> categories) {
        //1.空集合会生成 IN ()，MySQL 直接报语法错；「没有类别要回收」就是什么都不做
        if (categories.isEmpty()) {
            return;
        }

        //2.类别按名字落库，与 publish 写入时同一口径（不依赖全局枚举 TypeHandler 配置）
        notificationMapper.deleteRemindersByTicket(ticketId,
                categories.stream().map(NotificationCategory::name).toList());
    }

    /**
     * 站内信分页查询。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationResult> page(NotificationQuery query) {
        // 1. 收件人只由认证上下文决定
        long currentUserId = currentRecipientPort.currentUserId();

        // 2. 分页对象复用父类校验（页码与每页大小从 1 开始，每页上限 100）
        Page<Notification> page = new Page<>(query.getCurrent(), query.getSize());

        // 3. 「只看未读」与「全部」是两条固定的手写 SQL，排序固定为 id 倒序
        IPage<Notification> result = Boolean.TRUE.equals(query.getUnreadOnly())
                ? notificationMapper.selectUnreadRecipientPage(page, currentUserId)
                : notificationMapper.selectRecipientPage(page, currentUserId);

        return PageResult.from(result, NotificationServiceImpl::toResult);
    }

    /**
     * 站内信未读消息统计。
     */
    @Override
    @Transactional(readOnly = true)
    public NotificationUnreadCountResult unreadCount() {
        long currentUserId = currentRecipientPort.currentUserId();
        return new NotificationUnreadCountResult(notificationMapper.countUnread(currentUserId));
    }
    /**
     * 标记消息已读。
     */
    @Override
    @Transactional
    public void markRead(long notificationId) {
        long currentUserId = currentRecipientPort.currentUserId();

        // 1. 归属判定：他人或不存在统一 404，不暴露存在性
        if (notificationMapper.selectOwnedId(notificationId, currentUserId) == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "NOTIFICATION_NOT_FOUND",
                    "站内信不存在");
        }

        // 2. 条件更新只命中未读：已读时影响 0 行，重复调用幂等
        notificationMapper.markRead(notificationId, currentUserId, now());
    }

    /**
     * 标记所有消息已读。
     */
    @Override
    @Transactional
    public void markAllRead() {
        long currentUserId = currentRecipientPort.currentUserId();
        // 没有未读时影响 0 行，幂等
        notificationMapper.markAllRead(currentUserId, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static NotificationResult toResult(Notification notification) {
        return new NotificationResult(
                notification.getId(),
                notification.getCategory(),
                notification.getTitle(),
                notification.getBody(),
                notification.getTicketId(),
                notification.getTicketNo(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
