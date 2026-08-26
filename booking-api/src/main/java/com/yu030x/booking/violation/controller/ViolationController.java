package com.yu030x.booking.violation.controller;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.violation.service.ViolationService;
import com.yu030x.booking.violation.vo.ViolationView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/violations")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ViolationController {
    private final ViolationService violationService;
    private final BookingPrincipalAccessor principalAccessor;

    public ViolationController(ViolationService violationService,
            BookingPrincipalAccessor principalAccessor) {
        this.violationService = violationService;
        this.principalAccessor = principalAccessor;
    }

    @GetMapping
    Result<PageResult<ViolationView>> list(
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(violationService.pageForCurrentUser(
                principalAccessor.current().id(), pageNumber, pageSize));
    }
}
