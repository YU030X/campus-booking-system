package com.yu030x.booking.checkin.controller;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.checkin.service.CheckInService;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckInController {
    private final CheckInService checkInService;
    private final BookingPrincipalAccessor principalAccessor;

    public CheckInController(CheckInService checkInService,
            BookingPrincipalAccessor principalAccessor) {
        this.checkInService = checkInService;
        this.principalAccessor = principalAccessor;
    }

    @PostMapping("/{id}/check-in")
    Result<BookingView> checkIn(@PathVariable String id) {
        long bookingId;
        try {
            bookingId = Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid booking id");
        }
        return Result.success(checkInService.checkIn(principalAccessor.current().id(), bookingId));
    }
}
