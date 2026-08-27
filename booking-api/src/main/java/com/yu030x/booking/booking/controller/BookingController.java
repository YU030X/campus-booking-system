package com.yu030x.booking.booking.controller;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.service.BookingService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BookingController {
    private final BookingService bookingService;
    private final BookingPrincipalAccessor principalAccessor;

    public BookingController(BookingService bookingService, BookingPrincipalAccessor principalAccessor) {
        this.bookingService = bookingService;
        this.principalAccessor = principalAccessor;
    }

    @PostMapping
    ResponseEntity<Result<BookingView>> create(@Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(bookingService.create(principalAccessor.current(), request)));
    }

    @GetMapping
    Result<PageResult<BookingView>> list(
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        return Result.success(bookingService.list(
                principalAccessor.current().id(), pageNumber, pageSize, parseStatus(status)));
    }

    @GetMapping("/{id}")
    Result<BookingView> detail(@PathVariable String id) {
        long bookingId;
        try {
            bookingId = Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid booking id");
        }
        return Result.success(bookingService.detail(principalAccessor.current().id(), bookingId));
    }

    private BookingStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return BookingStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid status");
        }
    }
}
