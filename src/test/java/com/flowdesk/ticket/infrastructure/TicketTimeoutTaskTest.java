package com.flowdesk.ticket.infrastructure;

import com.flowdesk.ticket.application.service.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单超时任务的调度协议：一轮按固定次序跑四段，且每一段独立兜异常。
 *
 * <p>被测类直接 {@code new} 出来。它守的是调度层那层"系统性失败不外溢"的边界——
 * 一段抛 RuntimeException 若被放出去，Spring 的调度器会把它记成任务失败，
 * 但更重要的是后面几段这一轮就白跑了：到期工单没被处置、提醒没发。
 * 用例逐段注入异常，断言其余三段仍被调用，这就是"四段相互独立"的完整证据。</p>
 *
 * <p>服务层内部的单条失败隔离（一张工单失败不影响同批其余）由
 * {@code TicketTimeoutServiceImplTest} 覆盖，两者是两层，本类只管段与段之间。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketTimeoutTaskTest {

    @Mock
    private TicketService ticketService;

    private TicketTimeoutTask task;

    @Test
    void scanAndDisposeRunsAllFourSegmentsInOrder() {
        task = new TicketTimeoutTask(ticketService);

        task.scanAndDispose();

        InOrder order = inOrder(ticketService);
        order.verify(ticketService).publishDueSoonReminders(TicketTimeoutTask.BATCH_SIZE);
        order.verify(ticketService).completeExpiredConfirmations(TicketTimeoutTask.BATCH_SIZE);
        order.verify(ticketService).closeExpiredSupplements(TicketTimeoutTask.BATCH_SIZE);
        order.verify(ticketService).recallExpiredCancelReminders(TicketTimeoutTask.BATCH_SIZE);
    }

    /** 单轮上限统一传 {@code BATCH_SIZE}：调度层不各自拍一个数字。 */
    @Test
    void allSegmentsShareTheSameBatchSize() {
        task = new TicketTimeoutTask(ticketService);

        task.scanAndDispose();

        assertThat(TicketTimeoutTask.BATCH_SIZE).isEqualTo(200);
        verify(ticketService).publishDueSoonReminders(200);
        verify(ticketService).completeExpiredConfirmations(200);
        verify(ticketService).closeExpiredSupplements(200);
        verify(ticketService).recallExpiredCancelReminders(200);
    }

    /** 提醒段整体失败：其余三段照跑。 */
    @Test
    void reminderFailureDoesNotStopTheDisposalSegments() {
        task = new TicketTimeoutTask(ticketService);
        when(ticketService.publishDueSoonReminders(anyInt()))
                .thenThrow(new IllegalStateException("提醒段整体失败"));

        assertThatCode(task::scanAndDispose).doesNotThrowAnyException();

        verify(ticketService).completeExpiredConfirmations(TicketTimeoutTask.BATCH_SIZE);
        verify(ticketService).closeExpiredSupplements(TicketTimeoutTask.BATCH_SIZE);
        verify(ticketService).recallExpiredCancelReminders(TicketTimeoutTask.BATCH_SIZE);
    }

    /** 自动完成段整体失败：提醒已在它之前跑完，自动关闭与回收必须继续。 */
    @Test
    void confirmationFailureDoesNotStopLaterSegments() {
        task = new TicketTimeoutTask(ticketService);
        when(ticketService.completeExpiredConfirmations(anyInt()))
                .thenThrow(new IllegalStateException("自动完成段整体失败"));

        assertThatCode(task::scanAndDispose).doesNotThrowAnyException();

        verify(ticketService).publishDueSoonReminders(TicketTimeoutTask.BATCH_SIZE);
        verify(ticketService).closeExpiredSupplements(TicketTimeoutTask.BATCH_SIZE);
        verify(ticketService).recallExpiredCancelReminders(TicketTimeoutTask.BATCH_SIZE);
    }

    /** 自动关闭段整体失败：回收段仍要跑（它管的是另一批行）。 */
    @Test
    void supplementFailureDoesNotStopTheRecallSegment() {
        task = new TicketTimeoutTask(ticketService);
        when(ticketService.closeExpiredSupplements(anyInt()))
                .thenThrow(new IllegalStateException("自动关闭段整体失败"));

        assertThatCode(task::scanAndDispose).doesNotThrowAnyException();

        verify(ticketService).recallExpiredCancelReminders(TicketTimeoutTask.BATCH_SIZE);
    }

    /** 最后一段失败同样不外溢：异常不能把整轮判成失败。 */
    @Test
    void recallFailureDoesNotEscapeTheTask() {
        task = new TicketTimeoutTask(ticketService);
        when(ticketService.recallExpiredCancelReminders(anyInt()))
                .thenThrow(new IllegalStateException("回收段整体失败"));

        assertThatCode(task::scanAndDispose).doesNotThrowAnyException();
    }

    /** 四段同时失败也不抛：这一轮全部失败但调度器仍能继续下一轮。 */
    @Test
    void allSegmentsFailingTogetherStillDoesNotEscape() {
        task = new TicketTimeoutTask(ticketService);
        when(ticketService.publishDueSoonReminders(anyInt()))
                .thenThrow(new IllegalStateException("提醒失败"));
        when(ticketService.completeExpiredConfirmations(anyInt()))
                .thenThrow(new IllegalStateException("自动完成失败"));
        when(ticketService.closeExpiredSupplements(anyInt()))
                .thenThrow(new IllegalStateException("自动关闭失败"));
        when(ticketService.recallExpiredCancelReminders(anyInt()))
                .thenThrow(new IllegalStateException("回收失败"));

        assertThatCode(task::scanAndDispose).doesNotThrowAnyException();
    }

    /**
     * 开关语义：{@code flowdesk.ticket.timeout-task-enabled=false} 时不装配本任务
     * （{@code matchIfMissing = true} 表示缺省开启）。断言的是注解契约本身——
     * 少了它，本机调试时想关掉扫描任务就只能改代码。
     */
    @Test
    void taskIsConditionallyEnabledByProperty() {
        ConditionalOnProperty annotation = TicketTimeoutTask.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation).as("超时任务必须可由配置关闭").isNotNull();
        assertThat(annotation.name()).containsExactly("flowdesk.ticket.timeout-task-enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
        assertThat(annotation.matchIfMissing()).as("缺省开启").isTrue();
    }

    /** 扫描节奏由 {@code flowdesk.ticket.timeout-scan-cron} 覆盖，默认每 5 分钟一轮。 */
    @Test
    void scanCronIsOverridableAndDefaultsToFiveMinutes() {
        assertThat(TicketTimeoutTask.CRON).contains("flowdesk.ticket.timeout-scan-cron");
        assertThat(TicketTimeoutTask.CRON).contains("0 */5 * * * *");
    }
}
