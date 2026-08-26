package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultBookingActions implements BookingActions {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int CANCEL_REASON_MAX_CODE_POINTS = 200;

    private final BookingMapper bookingMapper;
    private final BookingSlotReleaseService slotRelease;
    private final Clock clock;

    @Autowired
    public DefaultBookingActions(@Lazy BookingMapper bookingMapper,
            BookingSlotReleaseService slotRelease) {
        this(bookingMapper, slotRelease, Clock.system(SHANGHAI));
    }

    public DefaultBookingActions(BookingMapper bookingMapper,
            BookingSlotReleaseService slotRelease, Clock clock) {
        this.bookingMapper = bookingMapper;
        this.slotRelease = slotRelease;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BookingActionOutcome approve(long bookingId) {
        if (bookingMapper.approvePending(bookingId, now()) == 1) {
            return BookingActionOutcome.winner(BookingView.from(bookingMapper.selectActiveById(bookingId)));
        }
        return classify(bookingId, null, BookingStatus.CONFIRMED);
    }

    @Override
    @Transactional
    public BookingActionOutcome reject(long bookingId) {
        if (bookingMapper.rejectPending(bookingId, now()) == 1) {
            slotRelease.releaseTerminalSlots(bookingId);
            return BookingActionOutcome.winner(BookingView.from(bookingMapper.selectActiveById(bookingId)));
        }
        return classify(bookingId, null, BookingStatus.REJECTED);
    }

    @Override
    @Transactional
    public BookingActionOutcome cancel(long bookingId, long ownerUserId, LocalDateTime actionTime,
            String cancelReason) {
        if (actionTime == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid cancel time");
        }
        String reason = normalizedReason(cancelReason);
        if (bookingMapper.cancelActiveByOwner(bookingId, ownerUserId, reason, actionTime) == 1) {
            slotRelease.releaseTerminalSlots(bookingId);
            return BookingActionOutcome.winner(
                    BookingView.from(bookingMapper.selectActiveByIdAndUser(bookingId, ownerUserId)));
        }
        return classify(bookingId, ownerUserId, BookingStatus.CANCELLED);
    }

    @Override
    @Transactional
    public BookingActionOutcome checkIn(long bookingId, long ownerUserId, LocalDateTime checkinTime) {
        if (checkinTime == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid check-in time");
        }
        if (bookingMapper.checkInConfirmedByOwner(bookingId, ownerUserId, checkinTime, now()) == 1) {
            return BookingActionOutcome.winner(
                    BookingView.from(bookingMapper.selectActiveByIdAndUser(bookingId, ownerUserId)));
        }
        return classify(bookingId, ownerUserId, BookingStatus.CHECKED_IN);
    }

    @Override
    @Transactional
    public BookingActionOutcome markNoShow(long bookingId) {
        if (bookingMapper.markNoShowConfirmed(bookingId, now()) == 1) {
            slotRelease.releaseTerminalSlots(bookingId);
            return BookingActionOutcome.winner(BookingView.from(bookingMapper.selectActiveById(bookingId)));
        }
        return classify(bookingId, null, BookingStatus.NO_SHOW);
    }

    private BookingActionOutcome classify(long bookingId, Long ownerUserId, BookingStatus targetStatus) {
        BookingEntity entity = ownerUserId == null
                ? bookingMapper.selectActiveById(bookingId)
                : bookingMapper.selectActiveByIdAndUser(bookingId, ownerUserId);
        if (entity == null) {
            return BookingActionOutcome.notFound();
        }
        BookingView currentView = BookingView.from(entity);
        if (entity.getStatus() == targetStatus) {
            return BookingActionOutcome.alreadyCompleted(currentView);
        }
        return BookingActionOutcome.illegalTransition(currentView);
    }

    private String normalizedReason(String cancelReason) {
        if (cancelReason == null || cancelReason.isBlank()) {
            return null;
        }
        String trimmed = cancelReason.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > CANCEL_REASON_MAX_CODE_POINTS) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid cancel reason");
        }
        return trimmed;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
