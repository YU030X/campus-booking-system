package com.yu030x.booking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yu030x.booking.approval.entity.ApprovalRecordEntity;
import com.yu030x.booking.approval.mapper.ApprovalRecordMapper;
import com.yu030x.booking.approval.service.ApprovalService;
import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingAdminReads;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.violation.port.ViolationPort;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApprovalServiceTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0, 0);
    private static final long BOOKING_ID = 9L;
    private static final long OPERATOR_ID = 3L;

    private BookingAdminReads bookingAdminReads;
    private BookingActions bookingActions;
    private ViolationPort violationPort;
    private ApprovalRecordMapper approvalRecordMapper;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        bookingAdminReads = mock(BookingAdminReads.class);
        bookingActions = mock(BookingActions.class);
        violationPort = mock(ViolationPort.class);
        approvalRecordMapper = mock(ApprovalRecordMapper.class);
        service = new ApprovalService(bookingAdminReads, bookingActions, violationPort,
                approvalRecordMapper, Clock.fixed(NOW.atZone(SHANGHAI).toInstant(), SHANGHAI), SHANGHAI);
    }

    @Test
    void approveWinnerInsertsOneApproveRecordAndReturnsCurrentView() {
        when(bookingActions.approve(BOOKING_ID))
                .thenReturn(outcome(BookingActionOutcome.Result.WINNER));

        BookingView view = service.approve(BOOKING_ID, OPERATOR_ID, null);

        assertThat(view.status()).isEqualTo(BookingStatus.CANCELLED);
        ArgumentCaptor<ApprovalRecordEntity> captor =
                ArgumentCaptor.forClass(ApprovalRecordEntity.class);
        verify(approvalRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(captor.getValue().getApproverId()).isEqualTo(OPERATOR_ID);
        assertThat(captor.getValue().getAction()).isEqualTo("APPROVE");
        assertThat(captor.getValue().getComment()).isNull();
    }

    @Test
    void rejectWinnerInsertsOneRejectRecordWithComment() {
        when(bookingActions.reject(BOOKING_ID))
                .thenReturn(outcome(BookingActionOutcome.Result.WINNER));

        service.reject(BOOKING_ID, OPERATOR_ID, "材料不全");

        ArgumentCaptor<ApprovalRecordEntity> captor =
                ArgumentCaptor.forClass(ApprovalRecordEntity.class);
        verify(approvalRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("REJECT");
        assertThat(captor.getValue().getComment()).isEqualTo("材料不全");
    }

    @Test
    void repeatedIdenticalApproveAfterTargetStateInsertsNoSecondRecord() {
        when(bookingActions.approve(BOOKING_ID))
                .thenReturn(outcome(BookingActionOutcome.Result.WINNER))
                .thenReturn(outcome(BookingActionOutcome.Result.ALREADY_COMPLETED));

        service.approve(BOOKING_ID, OPERATOR_ID, null);
        BookingView second = service.approve(BOOKING_ID, OPERATOR_ID, null);

        assertThat(second.status()).isNotEqualTo(BookingStatus.PENDING_APPROVAL);
        verify(approvalRecordMapper, times(1)).insert(any());
    }

    @Test
    void illegalTransitionMapsTo409AndNotFoundTo40400() {
        when(bookingActions.approve(BOOKING_ID))
                .thenReturn(outcome(BookingActionOutcome.Result.ILLEGAL_TRANSITION))
                .thenReturn(BookingActionOutcome.notFound());

        assertThatThrownBy(() -> service.approve(BOOKING_ID, OPERATOR_ID, null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR));
        assertThatThrownBy(() -> service.approve(BOOKING_ID, OPERATOR_ID, null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(approvalRecordMapper);
    }

    @Test
    void pendingPageDelegatesToBookingAdminReads() {
        PageResult<BookingView> page =
                new PageResult<>(2, 50, 1L, List.of(view(BookingStatus.PENDING_APPROVAL)));
        when(bookingAdminReads.pagePendingApprovals(2, 50)).thenReturn(page);

        assertThat(service.pendingPage(2, 50)).isSameAs(page);
    }

    @Test
    void cancelExactlyTwoHoursBeforeStartIsExemptFromViolation() {
        LocalDateTime start = NOW.plusHours(2);
        when(bookingActions.cancel(eq(BOOKING_ID), eq(5L), eq(NOW), eq(null)))
                .thenReturn(winner(start));

        BookingView view = service.cancel(5L, BOOKING_ID, null);

        assertThat(view.status()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(violationPort);
    }

    @Test
    void cancelOneSecondInsideTwoHoursTriggersLateCancelPortOnce() {
        LocalDateTime start = NOW.plusHours(2).minusSeconds(1);
        when(bookingActions.cancel(eq(BOOKING_ID), eq(5L), eq(NOW), any()))
                .thenReturn(winner(start));

        service.cancel(5L, BOOKING_ID, "行程有变");

        verify(violationPort).recordLateCancel(BOOKING_ID, 5L);
    }

    @Test
    void cancelWellBeforeStartHasNoViolationSideEffect() {
        when(bookingActions.cancel(eq(BOOKING_ID), eq(5L), eq(NOW), eq(null)))
                .thenReturn(winner(NOW.plusHours(5)));

        service.cancel(5L, BOOKING_ID, null);

        verifyNoInteractions(violationPort);
    }

    @Test
    void repeatedCancellationAfterTargetStateReturnsViewWithoutAnySideEffect() {
        when(bookingActions.cancel(eq(BOOKING_ID), eq(5L), eq(NOW), eq(null)))
                .thenReturn(outcome(BookingActionOutcome.Result.ALREADY_COMPLETED));

        BookingView view = service.cancel(5L, BOOKING_ID, null);

        assertThat(view.status()).isNotEqualTo(BookingStatus.PENDING_APPROVAL);
        verifyNoInteractions(violationPort);
        verifyNoInteractions(approvalRecordMapper);
    }

    @Test
    void cancelAtOrAfterStartMapsIllegalTransitionTo409WithoutPortCall() {
        when(bookingActions.cancel(eq(BOOKING_ID), eq(5L), eq(NOW), eq(null)))
                .thenReturn(outcome(BookingActionOutcome.Result.ILLEGAL_TRANSITION));

        assertThatThrownBy(() -> service.cancel(5L, BOOKING_ID, null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
                    assertThat(e.errorCode.httpStatus).isEqualTo(409);
                });
        verifyNoInteractions(violationPort);
    }

    @Test
    void foreignMissingOrDeletedCancelSharesUniform404Masking() {
        for (long userId : new long[]{5L, 6L, 7L}) {
            when(bookingActions.cancel(BOOKING_ID, userId, NOW, null))
                    .thenReturn(BookingActionOutcome.notFound());

            assertThatThrownBy(() -> service.cancel(userId, BOOKING_ID, null))
                    .isInstanceOfSatisfying(BizException.class, e -> {
                        assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND);
                        assertThat(e.errorCode.httpStatus).isEqualTo(404);
                    });
        }
    }

    @Test
    void lateCancelBoundaryUsesInclusiveTwoHourExemption() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 26, 14, 0, 0);
        assertThat(service.isLateCancel(start, start.minusHours(2))).isFalse();
        assertThat(service.isLateCancel(start, start.minusHours(2).minusNanos(1))).isFalse();
        assertThat(service.isLateCancel(start, start.minusHours(2).plusNanos(1))).isTrue();
        assertThat(service.isLateCancel(start, start.minusMinutes(90))).isTrue();
        assertThat(service.isLateCancel(start, start)).isTrue();
    }

    private BookingActionOutcome outcome(BookingActionOutcome.Result result) {
        return switch (result) {
            case WINNER -> winner(LocalDateTime.of(2026, 8, 26, 15, 0));
            case ALREADY_COMPLETED -> BookingActionOutcome.alreadyCompleted(
                    view(BookingStatus.CONFIRMED));
            case ILLEGAL_TRANSITION -> BookingActionOutcome.illegalTransition(
                    view(BookingStatus.REJECTED));
            case NOT_FOUND -> BookingActionOutcome.notFound();
        };
    }

    private BookingActionOutcome winner(LocalDateTime startTime) {
        BookingView base = view(BookingStatus.CANCELLED);
        BookingView withStart = new BookingView(base.id(), base.bookingNo(), base.userId(),
                base.resourceId(), startTime, startTime.plusHours(1), base.purpose(),
                base.attendeeCount(), base.status(), base.checkinTime(), base.cancelTime(),
                base.cancelReason(), base.createdAt(), base.updatedAt());
        return BookingActionOutcome.winner(withStart);
    }

    private BookingView view(BookingStatus status) {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                NOW.plusHours(2), NOW.plusHours(3),
                null, null, status, null, null, null,
                NOW.minusHours(1), NOW.minusHours(1));
    }
}
