package com.yu030x.booking.checkin.service;

import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.log.annotation.OperationLog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckInService {
    static final int WINDOW_MINUTES = 15;

    private final BookingService bookingService;
    private final BookingActions bookingActions;
    private final Clock clock;
    private final ZoneId zoneId;

    @Autowired
    public CheckInService(BookingService bookingService, BookingActions bookingActions,
            Clock jwtClock) {
        this(bookingService, bookingActions, jwtClock, ZoneId.of("Asia/Shanghai"));
    }

    public CheckInService(BookingService bookingService, BookingActions bookingActions,
            Clock clock, ZoneId zoneId) {
        this.bookingService = bookingService;
        this.bookingActions = bookingActions;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    @OperationLog("booking_check_in")
    public BookingView checkIn(long userId, long bookingId) {
        BookingView current = bookingService.detail(userId, bookingId);
        if (current.status() == BookingStatus.CHECKED_IN) {
            return current;
        }
        if (current.status() != BookingStatus.CONFIRMED) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "booking is not check-in eligible");
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(zoneId));
        if (!withinWindow(current.startTime(), now)) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "check-in window missed");
        }
        BookingActionOutcome outcome = bookingActions.checkIn(bookingId, userId, now);
        return switch (outcome.result()) {
            case WINNER, ALREADY_COMPLETED -> outcome.booking();
            case ILLEGAL_TRANSITION -> throw new BizException(
                    ErrorCode.BOOKING_ERROR, "booking is not check-in eligible");
            case NOT_FOUND -> throw new BizException(ErrorCode.NOT_FOUND, "booking not found");
        };
    }

    static boolean withinWindow(LocalDateTime startTime, LocalDateTime now) {
        return !now.isBefore(startTime.minusMinutes(WINDOW_MINUTES))
                && !now.isAfter(startTime.plusMinutes(WINDOW_MINUTES));
    }
}
