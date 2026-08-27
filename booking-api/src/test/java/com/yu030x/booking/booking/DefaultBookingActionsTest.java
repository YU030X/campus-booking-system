package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingSlotReleaseService;
import com.yu030x.booking.booking.service.DefaultBookingActions;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultBookingActionsTest {
    private static final long BOOKING_ID = 9L;
    private static final long OWNER_ID = 5L;
    private static final LocalDateTime NOW = LocalDate.of(2026, 8, 26).atTime(10, 0);
    private static final LocalDateTime ACTION_TIME = LocalDate.of(2026, 8, 26).atTime(9, 30);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BookingMapper bookingMapper;
    private BookingSlotReleaseService slotRelease;
    private BookingActions actions;

    @BeforeEach
    void setUp() {
        bookingMapper = mock(BookingMapper.class);
        slotRelease = mock(BookingSlotReleaseService.class);
        actions = new DefaultBookingActions(bookingMapper, slotRelease,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    private BookingEntity entity(BookingStatus status) {
        BookingEntity entity = new BookingEntity();
        entity.setId(BOOKING_ID);
        entity.setUserId(OWNER_ID);
        entity.setStatus(status);
        entity.setDeleted(0);
        return entity;
    }

    @Test
    void approveWinsOnlyFromPendingAndNeverReleasesSlots() {
        when(bookingMapper.approvePending(BOOKING_ID, NOW)).thenReturn(1);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(entity(BookingStatus.CONFIRMED));

        BookingActionOutcome outcome = actions.approve(BOOKING_ID);

        assertEquals(BookingActionOutcome.Result.WINNER, outcome.result());
        assertEquals(BookingStatus.CONFIRMED, outcome.booking().status());
        verifyNoInteractions(slotRelease);
    }

    @Test
    void repeatedApproveIsIdenticalIdempotentWithoutSideEffects() {
        when(bookingMapper.approvePending(BOOKING_ID, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(entity(BookingStatus.CONFIRMED));

        BookingActionOutcome outcome = actions.approve(BOOKING_ID);

        assertEquals(BookingActionOutcome.Result.ALREADY_COMPLETED, outcome.result());
        verifyNoInteractions(slotRelease);
    }

    @Test
    void illegalTransitionsReturnCurrentViewWithoutSideEffects() {
        when(bookingMapper.approvePending(BOOKING_ID, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(entity(BookingStatus.REJECTED));

        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.approve(BOOKING_ID).result());

        when(bookingMapper.rejectPending(BOOKING_ID, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(entity(BookingStatus.CONFIRMED));
        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.reject(BOOKING_ID).result());

        verifyNoInteractions(slotRelease);
    }

    @Test
    void missingOrDeletedBookingsAreIndistinguishableNotFound() {
        when(bookingMapper.approvePending(BOOKING_ID, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(null);
        when(bookingMapper.cancelActiveByOwner(BOOKING_ID, OWNER_ID, null, ACTION_TIME)).thenReturn(0);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID)).thenReturn(null);

        BookingActionOutcome approve = actions.approve(BOOKING_ID);
        BookingActionOutcome cancel = actions.cancel(BOOKING_ID, OWNER_ID, ACTION_TIME, null);

        assertEquals(BookingActionOutcome.Result.NOT_FOUND, approve.result());
        assertNull(approve.booking());
        assertEquals(BookingActionOutcome.Result.NOT_FOUND, cancel.result());
    }

    @Test
    void foreignCancelIsMaskedAsNotFoundLikeMissingRecords() {
        long foreignOwnerId = 77L;
        when(bookingMapper.cancelActiveByOwner(BOOKING_ID, foreignOwnerId, null, ACTION_TIME)).thenReturn(0);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, foreignOwnerId)).thenReturn(null);

        BookingActionOutcome outcome = actions.cancel(BOOKING_ID, foreignOwnerId, ACTION_TIME, null);

        assertEquals(BookingActionOutcome.Result.NOT_FOUND, outcome.result());
        assertNull(outcome.booking());
        verifyNoInteractions(slotRelease);
    }

    @Test
    void winningRejectCancelsAndNoShowReleaseAllSlotsOnce() {
        when(bookingMapper.rejectPending(BOOKING_ID, NOW)).thenReturn(1);
        when(bookingMapper.selectActiveById(BOOKING_ID)).thenReturn(entity(BookingStatus.REJECTED));
        when(bookingMapper.cancelActiveByOwner(BOOKING_ID, OWNER_ID, "改期", ACTION_TIME)).thenReturn(1);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID))
                .thenReturn(entity(BookingStatus.CANCELLED));
        when(bookingMapper.markNoShowConfirmed(BOOKING_ID, NOW)).thenReturn(1);

        assertEquals(BookingActionOutcome.Result.WINNER, actions.reject(BOOKING_ID).result());
        assertEquals(BookingActionOutcome.Result.WINNER,
                actions.cancel(BOOKING_ID, OWNER_ID, ACTION_TIME, "  改期  ").result());
        assertEquals(BookingActionOutcome.Result.WINNER, actions.markNoShow(BOOKING_ID).result());

        verify(slotRelease, times(3)).releaseTerminalSlots(BOOKING_ID);
    }

    @Test
    void cancelNormalizesReasonAndPersistsItOnlyForTheWinner() {
        when(bookingMapper.cancelActiveByOwner(BOOKING_ID, OWNER_ID, "有事", ACTION_TIME)).thenReturn(1);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID))
                .thenReturn(entity(BookingStatus.CANCELLED));

        assertEquals(BookingActionOutcome.Result.WINNER,
                actions.cancel(BOOKING_ID, OWNER_ID, ACTION_TIME, " 有事 ").result());

        verify(bookingMapper).cancelActiveByOwner(BOOKING_ID, OWNER_ID, "有事", ACTION_TIME);
    }

    @Test
    void cancelRequiresCallerSuppliedActionTime() {
        BizException exception = assertThrows(BizException.class,
                () -> actions.cancel(BOOKING_ID, OWNER_ID, null, null));

        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode);
        verifyNoInteractions(bookingMapper);
    }

    @Test
    void cancelReasonBeyondTwoHundredCodePointsIsRejected() {
        String tooLong = "😀".repeat(201);

        BizException exception = assertThrows(BizException.class,
                () -> actions.cancel(BOOKING_ID, OWNER_ID, ACTION_TIME, tooLong));

        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode);
        verifyNoInteractions(slotRelease);
    }

    @Test
    void checkInRequiresTimeRecordsWinnerAndMasksForeignRows() {
        LocalDateTime checkinTime = NOW.plusHours(2);
        when(bookingMapper.checkInConfirmedByOwner(BOOKING_ID, OWNER_ID, checkinTime, NOW)).thenReturn(1);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID))
                .thenReturn(entity(BookingStatus.CHECKED_IN));

        BookingActionOutcome winner = actions.checkIn(BOOKING_ID, OWNER_ID, checkinTime);

        assertEquals(BookingActionOutcome.Result.WINNER, winner.result());
        assertEquals(BookingStatus.CHECKED_IN, winner.booking().status());
        verify(bookingMapper).checkInConfirmedByOwner(BOOKING_ID, OWNER_ID, checkinTime, NOW);
        verifyNoInteractions(slotRelease);

        when(bookingMapper.checkInConfirmedByOwner(BOOKING_ID, 88L, checkinTime, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, 88L)).thenReturn(null);
        assertEquals(BookingActionOutcome.Result.NOT_FOUND,
                actions.checkIn(BOOKING_ID, 88L, checkinTime).result());
    }

    @Test
    void repeatedCheckInDoesNotRewriteTimestampOrReleaseSlots() {
        when(bookingMapper.checkInConfirmedByOwner(BOOKING_ID, OWNER_ID, NOW, NOW)).thenReturn(0);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID))
                .thenReturn(entity(BookingStatus.CHECKED_IN));

        BookingActionOutcome outcome = actions.checkIn(BOOKING_ID, OWNER_ID, NOW);

        assertEquals(BookingActionOutcome.Result.ALREADY_COMPLETED, outcome.result());
        verify(bookingMapper, never()).markNoShowConfirmed(BOOKING_ID, NOW);
        verifyNoInteractions(slotRelease);
    }

    @Test
    void repeatedCancelReturnsCurrentViewWithoutSecondRelease() {
        when(bookingMapper.cancelActiveByOwner(BOOKING_ID, OWNER_ID, null, ACTION_TIME)).thenReturn(0);
        when(bookingMapper.selectActiveByIdAndUser(BOOKING_ID, OWNER_ID))
                .thenReturn(entity(BookingStatus.CANCELLED));

        BookingActionOutcome outcome = actions.cancel(BOOKING_ID, OWNER_ID, ACTION_TIME, null);

        assertEquals(BookingActionOutcome.Result.ALREADY_COMPLETED, outcome.result());
        verifyNoInteractions(slotRelease);
    }
}
