package com.yu030x.booking.approval.controller;

import com.yu030x.booking.approval.dto.CancelRequest;
import com.yu030x.booking.approval.service.ApprovalService;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BookingCancelController {
    private final ApprovalService approvalService;
    private final BookingPrincipalAccessor principalAccessor;

    public BookingCancelController(ApprovalService approvalService,
            BookingPrincipalAccessor principalAccessor) {
        this.approvalService = approvalService;
        this.principalAccessor = principalAccessor;
    }

    @PostMapping("/{id}/cancel")
    Result<BookingView> cancel(@PathVariable String id,
            @Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.cancelReason();
        return Result.success(approvalService.cancel(
                principalAccessor.current().id(), parseBookingId(id), reason));
    }

    private long parseBookingId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid booking id");
        }
    }
}
