package com.yu030x.booking.statistics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADMIN resource-usage envelope: exactly {fromDate,toDate,records}; no user
 * ids, names, phones, purposes, or raw booking rows.
 *
 * @param fromDate inclusive canonical yyyy-MM-dd (Asia/Shanghai)
 * @param toDate   inclusive canonical yyyy-MM-dd (Asia/Shanghai)
 * @param records  one aggregate per existing, non-deleted resource, resourceId ascending
 */
public record ResourceUsageResponse(String fromDate, String toDate,
                                    List<ResourceUsageAggregate> records) {

    /**
     * Frozen ResourceUsageAggregate shape. {@code resourceId} is a JSON string;
     * all counts/minutes are non-negative integers; {@code usageRate} is a
     * bounded decimal ratio with determinate scale, or null when the summed
     * schedulable denominator is zero.
     */
    public record ResourceUsageAggregate(
            String resourceId,
            String resourceName,
            Integer bookingCount,
            Integer completedCount,
            Integer cancelledCount,
            Integer noShowCount,
            Integer occupiedSlotMinutes,
            BigDecimal usageRate) {
    }
}
