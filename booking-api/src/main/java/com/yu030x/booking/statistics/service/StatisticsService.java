package com.yu030x.booking.statistics.service;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.statistics.dto.BookingStatusResponse;
import com.yu030x.booking.statistics.dto.ResourceUsageResponse;
import com.yu030x.booking.statistics.mapper.StatisticsMapper;
import com.yu030x.booking.statistics.projection.ResourceUsageRow;
import com.yu030x.booking.statistics.projection.StatusCountRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * ADMIN statistics aggregation. Strict inclusive date handling: canonical
 * yyyy-MM-dd only (STRICT resolver), from <= to, at most {@link #MAX_INCLUSIVE_DAYS}=366
 * days counted as DAYS+1; every violation is INVALID_PARAMETER before any
 * mapper call.
 *
 * <p>Result integrity is fail-closed: a null list or null row from the mapper,
 * an unknown or absent status label, a missing/negative COALESCED column, a
 * non-positive resource id or a blank resource name all abort with
 * INTERNAL_ERROR instead of silently becoming zeros. Only genuinely optional
 * value is {@code schedulableMinutes}: LEFT JOINed resources without rules map
 * to a zero denominator, which makes usageRate null. Responses keep the frozen
 * DTO shapes with the seven statuses zero-filled in canonical order.</p>
 */
@ConditionalOnProperty(name = "booking.statistics.enabled", havingValue = "true", matchIfMissing = false)
public class StatisticsService {

    static final int MAX_INCLUSIVE_DAYS = 366;
    private static final int RATIO_SCALE = 4;
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final List<String> FROZEN_STATUS_ORDER = List.of(
            "PENDING_APPROVAL", "CONFIRMED", "CHECKED_IN", "COMPLETED",
            "REJECTED", "CANCELLED", "NO_SHOW");

    private final StatisticsMapper mapper;

    public StatisticsService(StatisticsMapper mapper) {
        this.mapper = mapper;
    }

    public ResourceUsageResponse resourceUsage(String rawFromDate, String rawToDate) {
        LocalDate[] range = parseRange(rawFromDate, rawToDate);
        LocalDate from = range[0];
        LocalDate to = range[1];
        List<ResourceUsageRow> rows = mapper.selectResourceUsage(from.toString(), to.toString(),
                startOfDay(from), startOfDay(to.plusDays(1)));
        if (rows == null) {
            throw internal("statistics returned no resource rows payload");
        }
        List<ResourceUsageResponse.ResourceUsageAggregate> records =
                new ArrayList<>(rows.size());
        for (ResourceUsageRow row : rows) {
            if (row == null) {
                throw internal("statistics contained a null resource row");
            }
            records.add(toAggregate(row));
        }
        return new ResourceUsageResponse(from.toString(), to.toString(), List.copyOf(records));
    }

    public BookingStatusResponse bookingStatuses(String rawFromDate, String rawToDate) {
        LocalDate[] range = parseRange(rawFromDate, rawToDate);
        LocalDate from = range[0];
        LocalDate to = range[1];
        List<StatusCountRow> rows = mapper.selectBookingStatusCounts(
                startOfDay(from), startOfDay(to.plusDays(1)));
        if (rows == null) {
            throw internal("statistics returned no status rows payload");
        }
        Map<FrozenStatus, Long> byStatus = new EnumMap<>(FrozenStatus.class);
        for (StatusCountRow row : rows) {
            if (row == null) {
                throw internal("statistics contained a null status row");
            }
            FrozenStatus status = FrozenStatus.exact(row.getStatus());
            long count = requireCount(row.getCount());
            byStatus.merge(status, count, Long::sum);
        }
        // Absent frozen statuses are legitimately zero; everything else above is fatal.
        List<BookingStatusResponse.BookingStatusAggregate> records = new ArrayList<>();
        for (String name : FROZEN_STATUS_ORDER) {
            records.add(new BookingStatusResponse.BookingStatusAggregate(name,
                    Math.toIntExact(byStatus.getOrDefault(FrozenStatus.valueOf(name), 0L))));
        }
        return new BookingStatusResponse(from.toString(), to.toString(), List.copyOf(records));
    }

    /** Strict parsing gate: runs entirely before any mapper interaction. */
    private LocalDate[] parseRange(String rawFrom, String rawTo) {
        LocalDate from = parseDate(rawFrom, "fromDate");
        LocalDate to = parseDate(rawTo, "toDate");
        long spanInclusive = ChronoUnit.DAYS.between(from, to) + 1;
        if (spanInclusive < 1 || spanInclusive > MAX_INCLUSIVE_DAYS) {
            throw invalid("date range must satisfy fromDate<=toDate within "
                    + MAX_INCLUSIVE_DAYS + " days inclusive");
        }
        return new LocalDate[]{from, to};
    }

    private LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field + " is required");
        }
        try {
            return LocalDate.parse(raw, STRICT_DATE);
        } catch (DateTimeParseException failure) {
            throw invalid(field + " must be a strict yyyy-MM-dd date");
        }
    }

    private ResourceUsageResponse.ResourceUsageAggregate toAggregate(ResourceUsageRow row) {
        Long id = row.getResourceId();
        if (id == null || id <= 0) {
            throw internal("statistics row without usable resource id");
        }
        String name = row.getResourceName();
        if (name == null || name.isBlank()) {
            throw internal("statistics row without resource name");
        }
        long occupied = requiredMinute(row.getOccupiedSlotMinutes(), "occupiedSlotMinutes");
        // NULL here is legitimate: resources without rules produce a zero denominator.
        long schedulable = row.getSchedulableMinutes() == null ? 0 : row.getSchedulableMinutes();
        if (schedulable < 0) {
            throw internal("negative schedulable minutes for resource [" + id + "]");
        }
        BigDecimal usageRate = schedulable == 0
                ? null
                : clamp(BigDecimal.valueOf(occupied)
                        .divide(BigDecimal.valueOf(schedulable), RATIO_SCALE, RoundingMode.HALF_UP));
        return new ResourceUsageResponse.ResourceUsageAggregate(
                id.toString(),
                name,
                requiredCountAsInteger(row.getBookingCount()),
                requiredCountAsInteger(row.getCompletedCount()),
                requiredCountAsInteger(row.getCancelledCount()),
                requiredCountAsInteger(row.getNoShowCount()),
                Math.toIntExact(occupied),
                usageRate);
    }

    /**
     * The usage SQL COALESCEs every count, so a mapped null is a broken result
     * set rather than an implicit zero; negatives are equally fatal.
     */
    private Integer requiredCountAsInteger(Long value) {
        return Math.toIntExact(requiredMinute(value, "count"));
    }

    private long requiredMinute(Long value, String field) {
        if (value == null) {
            throw internal("statistics aggregate missing [" + field + "]");
        }
        if (value < 0) {
            throw internal("statistics aggregate out of bounds [" + field + "]");
        }
        return value;
    }

    private long requireCount(Long value) {
        if (value == null) {
            throw internal("status count missing");
        }
        if (value < 0) {
            throw internal("negative status count");
        }
        return value;
    }

    private BigDecimal clamp(BigDecimal ratio) {
        return ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                .setScale(RATIO_SCALE, RoundingMode.UNNECESSARY);
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_PARAMETER, message);
    }

    private BizException internal(String message) {
        return new BizException(ErrorCode.INTERNAL_ERROR, message);
    }

    /** Frozen status machine; unknown labels never pass, keeping PII-free outputs intact. */
    enum FrozenStatus {
        PENDING_APPROVAL, CONFIRMED, CHECKED_IN, COMPLETED, REJECTED, CANCELLED, NO_SHOW;

        static FrozenStatus exact(String value) {
            if (value != null) {
                for (FrozenStatus candidate : values()) {
                    if (candidate.name().equals(value)) {
                        return candidate;
                    }
                }
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "statistics row carries an unknown booking status");
        }
    }
}
