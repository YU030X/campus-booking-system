package com.yu030x.booking.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.checkin.service.CheckInService;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckInServiceTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0, 0);

    private BookingService bookingService;
    private BookingActions bookingActions;
    private CheckInService service;

    @BeforeEach
    void setUp() {
        bookingService = mock(BookingService.class);
        bookingActions = mock(BookingActions.class);
        service = new CheckInService(bookingService, bookingActions,
                Clock.fixed(NOW.atZone(SHANGHAI).toInstant(), SHANGHAI), SHANGHAI);
    }

    @Test
    void lowerBoundaryExactlyFifteenMinutesBeforeWins() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 12, 15), BookingStatus.CONFIRMED));
        when(bookingActions.checkIn(9L, 5L, NOW)).thenReturn(winner());

        assertThat(service.checkIn(5L, 9L)).isEqualTo(view());
        verify(bookingActions).checkIn(9L, 5L, NOW);
    }

    @Test
    void upperBoundaryExactlyFifteenMinutesAfterWins() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 11, 45), BookingStatus.CONFIRMED));
        when(bookingActions.checkIn(9L, 5L, NOW)).thenReturn(winner());

        assertThat(service.checkIn(5L, 9L)).isEqualTo(view());
        verify(bookingActions).checkIn(9L, 5L, NOW);
    }

    @Test
    void oneSecondBeforeWindowIsRejectedWithoutSideEffects() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 12, 15, 1), BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
                    assertThat(e.errorCode.httpStatus).isEqualTo(409);
                });
        verify(bookingActions, never()).checkIn(anyLong(), anyLong(), any());
    }

    @Test
    void oneSecondAfterWindowIsRejectedWithoutSideEffects() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 11, 44, 59), BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR));
        verify(bookingActions, never()).checkIn(anyLong(), anyLong(), any());
    }

    @Test
    void nonConfirmedStatusIsRejectedInsideWindow() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 12, 10), BookingStatus.PENDING_APPROVAL));

        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR));
        verify(bookingActions, never()).checkIn(anyLong(), anyLong(), any());
    }

    @Test
    void repeatedCheckInReturnsCurrentViewWithoutAnyActionCall() {
        BookingView checkedIn =
                bookingView(LocalDateTime.of(2026, 8, 26, 11, 55), BookingStatus.CHECKED_IN);
        when(bookingService.detail(5L, 9L)).thenReturn(checkedIn);

        assertThat(service.checkIn(5L, 9L)).isEqualTo(checkedIn);
        verify(bookingActions, never()).checkIn(anyLong(), anyLong(), any());
    }

    @Test
    void foreignMissingOrDeletedBookingsAreMaskedAs404ThroughT07Seam() {
        when(bookingService.detail(5L, 9L))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "booking not found"));

        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND));
        verify(bookingActions, never()).checkIn(anyLong(), anyLong(), any());
    }

    @Test
    void racingTransitionLossMapsTo409AndNotFoundOutcomeTo404() {
        when(bookingService.detail(5L, 9L))
                .thenReturn(bookingView(LocalDateTime.of(2026, 8, 26, 12, 5), BookingStatus.CONFIRMED));
        when(bookingActions.checkIn(9L, 5L, NOW))
                .thenReturn(BookingActionOutcome.illegalTransition(view()));

        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR));

        when(bookingActions.checkIn(9L, 5L, NOW)).thenReturn(BookingActionOutcome.notFound());
        assertThatThrownBy(() -> service.checkIn(5L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private BookingView bookingView(LocalDateTime start, BookingStatus status) {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                start, start.plusHours(1),
                null, null, status, null, null, null,
                LocalDateTime.of(2026, 8, 26, 10, 0), LocalDateTime.of(2026, 8, 26, 10, 0));
    }

    private BookingActionOutcome winner() {
        return BookingActionOutcome.winner(view());
    }

    private BookingView view() {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                LocalDateTime.of(2026, 8, 26, 12, 15), LocalDateTime.of(2026, 8, 26, 13, 15),
                null, null, BookingStatus.CHECKED_IN, LocalDateTime.of(2026, 8, 26, 12, 0, 0),
                null, null,
                LocalDateTime.of(2026, 8, 26, 10, 0), LocalDateTime.of(2026, 8, 26, 10, 0));
    }
}
