package com.yu030x.booking.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.statistics.dto.BookingStatusResponse;
import com.yu030x.booking.statistics.dto.ResourceUsageResponse;
import com.yu030x.booking.statistics.mapper.StatisticsMapper;
import com.yu030x.booking.statistics.projection.ResourceUsageRow;
import com.yu030x.booking.statistics.projection.StatusCountRow;
import com.yu030x.booking.statistics.service.StatisticsService;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatisticsServiceMappingTest {
    private static final List<String> NO_PII_FRAGMENTS = List.of(
            "userId", "userName", "phone", "purpose", "bookingNo", "cancelReason",
            "realName", "attendee");

    private StatisticsMapper mapper;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        mapper = org.mockito.Mockito.mock(StatisticsMapper.class);
        service = new StatisticsService(mapper);
    }

    private ResourceUsageRow row(long id, String name, long bookings, long completed,
            long cancelled, long noShow, long occupied, Long schedulable) {
        ResourceUsageRow row = new ResourceUsageRow();
        row.setResourceId(id);
        row.setResourceName(name);
        row.setBookingCount(bookings);
        row.setCompletedCount(completed);
        row.setCancelledCount(cancelled);
        row.setNoShowCount(noShow);
        row.setOccupiedSlotMinutes(occupied);
        row.setSchedulableMinutes(schedulable);
        return row;
    }

    @Test
    void dtoShapesAreFrozenWithoutAnyPiiField() {
        assertThat(components(ResourceUsageResponse.class)).containsExactly("fromDate", "toDate", "records");
        assertThat(components(BookingStatusResponse.class)).containsExactly("fromDate", "toDate", "records");
        assertThat(Arrays.stream(ResourceUsageResponse.ResourceUsageAggregate.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("resourceId", "resourceName", "bookingCount", "completedCount",
                        "cancelledCount", "noShowCount", "occupiedSlotMinutes", "usageRate");
        assertThat(Arrays.stream(BookingStatusResponse.BookingStatusAggregate.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("status", "count");
        List<List<String>> nameSets = Arrays.asList(
                components(ResourceUsageResponse.class),
                components(BookingStatusResponse.class),
                Arrays.stream(ResourceUsageResponse.ResourceUsageAggregate.class.getRecordComponents())
                        .map(RecordComponent::getName).toList(),
                Arrays.stream(BookingStatusResponse.BookingStatusAggregate.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        for (List<String> names : nameSets) {
            for (String name : names) {
                for (String fragment : NO_PII_FRAGMENTS) {
                    assertThat(name.toLowerCase()).doesNotContain(fragment.toLowerCase());
                }
            }
        }
    }

    private List<String> components(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void resourcesMapToStringIdKeepOrderAndNeverExposeNegativeCounts() {
        org.mockito.Mockito.when(mapper.selectResourceUsage(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                any(), any())).thenReturn(List.of(
                row(3, "琴房 A301", 9, 4, 2, 1, 90, 600L),
                row(7, "会议室 B102", 0, 0, 0, 0, 0, null)));

        ResourceUsageResponse response = service.resourceUsage("2026-08-01", "2026-08-07");

        assertThat(response.fromDate()).isEqualTo("2026-08-01");
        assertThat(response.toDate()).isEqualTo("2026-08-07");
        assertThat(response.records()).hasSize(2);
        ResourceUsageResponse.ResourceUsageAggregate first = response.records().get(0);
        assertThat(first.resourceId()).isEqualTo("3");
        assertThat(first.usageRate()).isEqualByComparingTo(new BigDecimal("0.1500"));
        ResourceUsageResponse.ResourceUsageAggregate second = response.records().get(1);
        assertThat(second.resourceId()).isEqualTo("7");
        assertThat(second.bookingCount()).isZero();
        // LEFT JOIN without rules: denominator stays zero so the ratio is null.
        assertThat(second.usageRate()).isNull();
    }

    @Test
    void ratioIsScaledToFourDecimalsClampedIntoZeroToOneAndNullOnZeroDenominator() {
        org.mockito.Mockito.when(mapper.selectResourceUsage(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                any(), any())).thenReturn(List.of(
                row(1, "R1", 1, 1, 0, 0, 10, 60L),
                row(2, "R2 overbooked", 1, 1, 0, 0, 900, 600L),
                row(3, "R3 zero schedule", 0, 0, 0, 0, 120, 0L),
                row(4, "R4 rule-less LEFT JOIN", 2, 1, 1, 0, 120, null)));

        List<ResourceUsageResponse.ResourceUsageAggregate> records =
                service.resourceUsage("2026-08-01", "2026-08-02").records();

        assertThat(records.get(0).usageRate()).isEqualByComparingTo(new BigDecimal("0.1667"));
        assertThat(records.get(1).usageRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(records.get(2).usageRate()).isNull();
        assertThat(records.get(3).usageRate()).isNull();
        assertThat(records.get(3).occupiedSlotMinutes()).isEqualTo(120);
        for (ResourceUsageResponse.ResourceUsageAggregate record : records) {
            if (record.usageRate() != null) {
                assertThat(record.usageRate().scale()).isEqualTo(4);
                assertThat(record.usageRate())
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                        .isLessThanOrEqualTo(BigDecimal.ONE);
            }
        }
    }

    @Test
    void brokenUsageRowsAbortInsteadOfBeingDisguisedAsZeros() {
        record Case(String name, ResourceUsageRow payload) { }
        List<Case> cases = List.of(
                new Case("zeroId", row(0, "n", 0, 0, 0, 0, 0, 100L)),
                new Case("negativeId", row(-2, "n", 0, 0, 0, 0, 0, 100L)),
                new Case("missingBookingCount",
                        rowWithNulls(9L, "n", null, 0L, 0L, 0L, 0L, 100L)),
                new Case("missingCompleted",
                        rowWithNulls(9L, "n", 3L, null, 0L, 0L, 0L, 100L)),
                new Case("missingCancelled",
                        rowWithNulls(9L, "n", 3L, 0L, null, 0L, 0L, 100L)),
                new Case("missingNoShow",
                        rowWithNulls(9L, "n", 3L, 0L, 0L, null, 0L, 100L)),
                new Case("missingOccupied",
                        rowWithNulls(9L, "n", 3L, 0L, 0L, 0L, null, 100L)),
                new Case("negativeOccupied", row(9, "n", 1, 0, 0, 0, -30, 100L)),
                new Case("negativeSchedulable", row(9, "n", 1, 0, 0, 0, 10, -60L)),
                new Case("blankName", row(9, "  ", 1, 0, 0, 0, 10, 100L)),
                new Case("nameMissingRow", rowWithNulls(9L, null, 1L, 0L, 0L, 0L, 10L, 100L)),
                new Case("idMissingRow", rowWithNulls(null, "n", 1L, 0L, 0L, 0L, 10L, 100L)));

        for (Case item : cases) {
            stubUsageRows(List.of(item.payload()));
            assertThatThrownBy(() -> service.resourceUsage("2026-08-01", "2026-08-02"))
                    .as(item.name())
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Test
    void nullListsAndNullRowsAreInternalErrorsRatherThanEmptyResults() {
        org.mockito.Mockito.when(mapper.selectResourceUsage(any(), any(), any(), any()))
                .thenReturn(null);
        assertThatThrownBy(() -> service.resourceUsage("2026-08-01", "2026-08-02"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));

        org.mockito.Mockito.when(mapper.selectResourceUsage(any(), any(), any(), any()))
                .thenReturn(java.util.Arrays.asList((ResourceUsageRow) null));
        assertThatThrownBy(() -> service.resourceUsage("2026-08-01", "2026-08-02"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));

        org.mockito.Mockito.when(mapper.selectBookingStatusCounts(any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.bookingStatuses("2026-08-01", "2026-08-02"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));

        org.mockito.Mockito.when(mapper.selectBookingStatusCounts(any(), any()))
                .thenReturn(java.util.Arrays.asList((StatusCountRow) null));
        assertThatThrownBy(() -> service.bookingStatuses("2026-08-01", "2026-08-02"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void statusesZeroFillMissingFrozenEntriesInCanonicalOrder() {
        org.mockito.Mockito.when(mapper.selectBookingStatusCounts(any(), any()))
                .thenReturn(List.of(statusRow("COMPLETED", 5L),
                        statusRow("PENDING_APPROVAL", 2L),
                        statusRow("NO_SHOW", 1L)));

        BookingStatusResponse response = service.bookingStatuses("2026-08-01", "2026-08-31");

        assertThat(response.records()).extracting(BookingStatusResponse.BookingStatusAggregate::status)
                .containsExactly("PENDING_APPROVAL", "CONFIRMED", "CHECKED_IN", "COMPLETED",
                        "REJECTED", "CANCELLED", "NO_SHOW");
        assertThat(response.records()).extracting(BookingStatusResponse.BookingStatusAggregate::count)
                .containsExactly(2, 0, 0, 5, 0, 0, 1);
    }

    @Test
    void poisonedStatusRowsAbortInsteadOfSilentIgnoreOrFakeZero() {
        List<StatusCountRow> cases = List.of(
                statusRow("GARBAGE_STATE", 9L),
                statusRow(null, 4L),
                statusRow("", 4L),
                statusRow("COMPLETED", null),
                statusRow("COMPLETED", -3L));

        for (int index = 0; index < cases.size(); index++) {
            StatusCountRow case_ = cases.get(index);
            org.mockito.Mockito.when(mapper.selectBookingStatusCounts(any(), any()))
                    .thenReturn(java.util.Collections.singletonList(case_));
            assertThatThrownBy(() -> service.bookingStatuses("2026-08-01", "2026-08-31"))
                    .as("poisoned status case %d", index)
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    /** Builds a row with explicit nullable fields to assert missing-column fatal paths. */
    private ResourceUsageRow rowWithNulls(Long id, String name, Long bookings, Long completed,
            Long cancelled, Long noShow, Long occupied, Long schedulable) {
        ResourceUsageRow row = new ResourceUsageRow();
        row.setResourceId(id);
        row.setResourceName(name);
        row.setBookingCount(bookings);
        row.setCompletedCount(completed);
        row.setCancelledCount(cancelled);
        row.setNoShowCount(noShow);
        row.setOccupiedSlotMinutes(occupied);
        row.setSchedulableMinutes(schedulable);
        return row;
    }

    private StatusCountRow statusRow(String status, Long count) {
        StatusCountRow row = new StatusCountRow();
        row.setStatus(status);
        row.setCount(count);
        return row;
    }

    private void stubUsageRows(List<ResourceUsageRow> rows) {
        org.mockito.Mockito.when(mapper.selectResourceUsage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), any(), any()))
                .thenReturn(rows);
    }
}
