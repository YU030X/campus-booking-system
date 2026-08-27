package com.yu030x.booking.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.statistics.dto.ResourceUsageResponse;
import com.yu030x.booking.statistics.mapper.StatisticsMapper;
import com.yu030x.booking.statistics.service.StatisticsService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatisticsServiceValidationTest {
    private StatisticsMapper mapper;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(StatisticsMapper.class);
        whenRows();
        service = new StatisticsService(mapper);
    }

    @SuppressWarnings("unchecked")
    private void whenRows() {
        org.mockito.Mockito.when(mapper.selectResourceUsage(anyString(), anyString(), any(), any()))
                .thenReturn(List.<com.yu030x.booking.statistics.projection.ResourceUsageRow>of());
        org.mockito.Mockito.when(mapper.selectBookingStatusCounts(any(), any()))
                .thenReturn(List.<com.yu030x.booking.statistics.projection.StatusCountRow>of());
    }

    private void assertRejected(String from, String to) {
        assertThatThrownBy(() -> service.resourceUsage(from, to))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.bookingStatuses(from, to))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    @Test
    void missingBlankAndMalformedDatesAre40000BeforeAnyMapperUse() {
        assertRejected(null, "2026-08-27");
        assertRejected("2026-08-01", " ");
        assertRejected("2026-08-01", "not-a-date");
        assertRejected("26-08-01", "2026-08-02");
        assertRejected("2026-8-1", "2026-08-02");
        assertRejected("2026-13-01", "2026-08-02");
        assertRejected("2026-02-29", "2026-08-02");
        assertRejected("2026-04-31", "2026-08-02");
        verifyNoInteractions(mapper);
    }

    @Test
    void reversedRangeIsRejectedWithZeroMapperCalls() {
        assertRejected("2026-08-27", "2026-08-01");
        verifyNoInteractions(mapper);
    }

    @Test
    void inclusiveSpanOf366DaysIsAcceptedWhile367IsRejectedZeroingTheMapper() {
        LocalDate start = LocalDate.of(2026, 1, 1);

        ResourceUsageResponse fullYear =
                service.resourceUsage(start.toString(), start.plusDays(365).toString());
        assertThat(fullYear.fromDate()).isEqualTo("2026-01-01");
        assertThat(fullYear.toDate()).isEqualTo("2026-12-31");
        verify(mapper).selectResourceUsage(org.mockito.ArgumentMatchers.eq("2026-01-01"),
                org.mockito.ArgumentMatchers.eq("2026-12-31"), any(), any());

        assertThatThrownBy(() -> service.resourceUsage(start.toString(),
                start.plusDays(366).toString()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(mapper, never()).selectResourceUsage(org.mockito.ArgumentMatchers.eq("2026-01-01"),
                org.mockito.ArgumentMatchers.eq("2027-01-02"), any(), any());
        assertThatThrownBy(() -> service.bookingStatuses(start.toString(),
                start.plusDays(366).toString()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void leapDayIsStrictlyParseableInsideItsOwnLeapYear() {
        ResourceUsageResponse response =
                service.resourceUsage("2028-02-28", "2028-03-01");

        assertThat(response.fromDate()).isEqualTo("2028-02-28");
        assertThat(response.toDate()).isEqualTo("2028-03-01");
        verify(mapper).selectResourceUsage(org.mockito.ArgumentMatchers.eq("2028-02-28"),
                org.mockito.ArgumentMatchers.eq("2028-03-01"),
                any(), any());
    }

    @Test
    void singleDayInclusiveRangeRunsExactlyOneQueryPerEndpoint() {
        service.resourceUsage("2026-08-27", "2026-08-27");
        service.bookingStatuses("2026-08-27", "2026-08-27");

        verify(mapper).selectResourceUsage(org.mockito.ArgumentMatchers.eq("2026-08-27"),
                org.mockito.ArgumentMatchers.eq("2026-08-27"),
                any(), any());
        verify(mapper).selectBookingStatusCounts(any(), any());
    }
}
