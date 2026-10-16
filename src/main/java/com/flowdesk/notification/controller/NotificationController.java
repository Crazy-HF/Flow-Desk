package com.flowdesk.notification.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.result.NotificationUnreadCountResult;
import com.flowdesk.notification.application.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信接口：本人范围，只要求已认证（基础安全链 {@code anyRequest().authenticated()}），
 * 不设权限码——契约未给站内信单独定义权限编码（{@code docs/api-design.md} 第 9 节）。
 */
@RestController
@RequestMapping("/fd/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 本人通知列表；{@code unreadOnly=true} 只看未读。 */
    @GetMapping
    public R<PageResult<NotificationResult>> page(@Valid @ModelAttribute NotificationQuery query) {
        return R.success(notificationService.page(query));
    }

    /** 本人未读条数，供顶栏徽标轮询。 */
    @GetMapping("/unread-count")
    public R<NotificationUnreadCountResult> unreadCount() {
        return R.success(notificationService.unreadCount());
    }

    /** 标记本人某条通知已读；他人或不存在的通知返回 404。 */
    @PostMapping("/{notificationId}/read")
    public R<Void> markRead(@PathVariable long notificationId) {
        notificationService.markRead(notificationId);
        return R.success();
    }

    /** 把本人全部未读标记为已读。 */
    @PostMapping("/actions/read-all")
    public R<Void> markAllRead() {
        notificationService.markAllRead();
        return R.success();
    }
}
