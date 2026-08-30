package com.yu030x.booking.statistics.controller;

import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.statistics.dto.BookingStatusResponse;
import com.yu030x.booking.statistics.dto.ResourceUsageResponse;
import com.yu030x.booking.statistics.service.StatisticsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only statistics routes; the existing global /api/v1/admin/**
 * security chain already enforces hasRole(ADMIN) (401 unauthenticated, 403
 * insufficient role), so this controller adds no security of its own. Raw
 * params are consumed as optional strings and strictly validated in the
 * service so missing/illegal dates always produce 40000 with zero mapper use.
 */
@RestController
@RequestMapping("/api/v1/admin/statistics")
@ConditionalOnProperty(name = "booking.statistics.enabled", havingValue = "true", matchIfMissing = false)
public class AdminStatisticsController {
    private final StatisticsService statisticsService;

    public AdminStatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/resources")
    Result<ResourceUsageResponse> resources(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return Result.success(statisticsService.resourceUsage(fromDate, toDate));
    }

    @GetMapping("/bookings")
    Result<BookingStatusResponse> bookings(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return Result.success(statisticsService.bookingStatuses(fromDate, toDate));
    }
}
