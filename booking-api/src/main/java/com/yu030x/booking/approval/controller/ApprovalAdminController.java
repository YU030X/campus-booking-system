package com.yu030x.booking.approval.controller;

import com.yu030x.booking.approval.dto.ApproveRequest;
import com.yu030x.booking.approval.dto.RejectRequest;
import com.yu030x.booking.approval.service.ApprovalService;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalAdminController {
    private final ApprovalService approvalService;
    private final BookingPrincipalAccessor principalAccessor;

    public ApprovalAdminController(ApprovalService approvalService,
            BookingPrincipalAccessor principalAccessor) {
        this.approvalService = approvalService;
        this.principalAccessor = principalAccessor;
    }

    @GetMapping("/approvals")
    Result<PageResult<BookingView>> pending(
            @RequestParam(defaultValue = "1") @Min(1) int pageNumber,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        return Result.success(approvalService.pendingPage(pageNumber, pageSize));
    }

    @PostMapping("/bookings/{id}/approve")
    Result<BookingView> approve(@PathVariable String id, @Valid @RequestBody ApproveRequest request) {
        return Result.success(approvalService.approve(
                parseBookingId(id), principalAccessor.current().id(), request.comment()));
    }

    @PostMapping("/bookings/{id}/reject")
    Result<BookingView> reject(@PathVariable String id, @Valid @RequestBody RejectRequest request) {
        return Result.success(approvalService.reject(
                parseBookingId(id), principalAccessor.current().id(), request.comment()));
    }

    private long parseBookingId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid booking id");
        }
    }
}
