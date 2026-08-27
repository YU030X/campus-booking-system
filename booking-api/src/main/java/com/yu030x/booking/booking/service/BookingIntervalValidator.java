package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class BookingIntervalValidator {
    private BookingIntervalValidator() {}

    public record ParsedInterval(long resourceId, LocalDateTime start, LocalDateTime end, LocalDate date) {}

    public static ParsedInterval validate(CreateBookingRequest request, LocalDateTime now) {
        if (request == null || request.resourceId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        long resourceId;
        try {
            resourceId = Long.parseLong(request.resourceId());
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid resource id");
        }
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "start must be before end");
        }
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "interval must stay in one day");
        }
        if (!aligned(start) || !aligned(end)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "times must align to :00 or :30 with zero seconds");
        }
        if (!start.isAfter(now)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "start must be in the future");
        }
        return new ParsedInterval(resourceId, start, end, start.toLocalDate());
    }

    private static boolean aligned(LocalDateTime value) {
        return value.getSecond() == 0
                && value.getNano() == 0
                && (value.getMinute() == 0 || value.getMinute() == 30);
    }
}
