package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.service.BookingIntervalValidator;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BookingIntervalValidatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 10, 0);

    private static CreateBookingRequest request(String resourceId, LocalDateTime start,
            LocalDateTime end) {
        return new CreateBookingRequest(resourceId, start, end, null, 1);
    }

    @Test
    void acceptsAlignedFutureSameDayInterval() {
        BookingIntervalValidator.ParsedInterval interval = BookingIntervalValidator.validate(
                request("42", NOW.withHour(14), NOW.withHour(16)), NOW);

        assertEquals(42L, interval.resourceId());
        assertEquals(NOW.toLocalDate(), interval.date());
        assertEquals(NOW.withHour(14), interval.start());
        assertEquals(NOW.withHour(16), interval.end());
    }

    @Test
    void rejectsReversedOrEmptyIntervals() {
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(request("1", NOW.plusHours(2), NOW.plusHours(1)), NOW));
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(request("1", NOW.plusHours(1), NOW.plusHours(1)), NOW));
    }

    @Test
    void rejectsCrossDayInterval() {
        BizException exception = assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(
                        request("1", LocalDateTime.of(2026, 8, 26, 23, 30),
                                LocalDateTime.of(2026, 8, 27, 0, 0)), NOW));
        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode);
    }

    @Test
    void rejectsNonAlignedMinutesSecondsAndNanos() {
        LocalDateTime day = NOW.toLocalDate().atTime(14, 0);
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(request("1", day, day.plusMinutes(15)), NOW));
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(
                        request("1", day.withSecond(1), day.withSecond(1).plusMinutes(30)), NOW));
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(
                        request("1", day.withNano(1), day.withNano(1).plusMinutes(30)), NOW));
    }

    @Test
    void rejectsStartAtNowAndPastStart() {
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(request("1", NOW, NOW.plusMinutes(30)), NOW));
        assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(
                        request("1", NOW.minusMinutes(30), NOW), NOW));
    }

    @Test
    void rejectsNonDecimalResourceId() {
        BizException exception = assertThrows(BizException.class,
                () -> BookingIntervalValidator.validate(
                        request("abc", NOW.plusHours(1), NOW.plusHours(2)), NOW));
        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode);
        assertTrue(exception.getMessage().contains("resource"));
    }
}
