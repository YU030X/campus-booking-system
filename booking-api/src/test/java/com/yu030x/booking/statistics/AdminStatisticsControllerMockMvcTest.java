package com.yu030x.booking.statistics;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.statistics.controller.AdminStatisticsController;
import com.yu030x.booking.statistics.dto.BookingStatusResponse;
import com.yu030x.booking.statistics.dto.ResourceUsageResponse;
import com.yu030x.booking.statistics.service.StatisticsService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Route/envelope behaviour of the ADMIN statistics controller. The real 401
 * (unauthenticated) and 403 (non-ADMIN) outcomes are produced by the global
 * /api/v1/admin/** security chain and therefore asserted end-to-end in the
 * opt-in MySQL skeleton; here they are emulated through the principal
 * accessor so the advice mapping stays pinned without Spring Security.
 */
class AdminStatisticsControllerMockMvcTest {
    private StatisticsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StatisticsService.class);
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new AdminStatisticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private void applyAuthFailure(BizException failure) {
        AdminStatisticsController guarded =
                new AdminStatisticsController(new GuardedService(failure));
        mvc = MockMvcBuilders.standaloneSetup(guarded)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(mapperOf()))
                .build();
    }

    private static ObjectMapper mapperOf() {
        return new Jackson2ObjectMapperBuilder().modules(new JavaTimeModule()).build();
    }

    /** Emulates the global chain outcome by surfacing its error code from the service layer. */
    private static final class GuardedService extends StatisticsService {
        private final BizException failure;

        GuardedService(BizException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public ResourceUsageResponse resourceUsage(String rawFromDate, String rawToDate) {
            throw failure;
        }

        @Override
        public BookingStatusResponse bookingStatuses(String rawFromDate, String rawToDate) {
            throw failure;
        }
    }

    @Test
    void resourcesEnvelopeIsCanonicalWithStringResourceIdAndNullableRatio() throws Exception {
        when(service.resourceUsage("2026-08-01", "2026-08-07"))
                .thenReturn(new ResourceUsageResponse("2026-08-01", "2026-08-07", List.of(
                        new ResourceUsageResponse.ResourceUsageAggregate("7", "琴房 A301",
                                9, 4, 2, 1, 90, new BigDecimal("0.1500")),
                        new ResourceUsageResponse.ResourceUsageAggregate("8", "会议室 B102",
                                0, 0, 0, 0, 0, null))));

        mvc.perform(get("/api/v1/admin/statistics/resources")
                        .param("fromDate", "2026-08-01").param("toDate", "2026-08-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.fromDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.toDate").value("2026-08-07"))
                .andExpect(jsonPath("$.data.records[0].resourceId").value("7"))
                .andExpect(jsonPath("$.data.records[0].bookingCount").value(9))
                .andExpect(jsonPath("$.data.records[0].occupiedSlotMinutes").value(90))
                .andExpect(jsonPath("$.data.records[0].usageRate").value(0.1500))
                .andExpect(jsonPath("$.data.records[1].resourceId").value("8"))
                .andExpect(jsonPath("$.data.records[1].usageRate").value(nullValue()));
    }

    @Test
    void bookingsEnvelopeListsSevenFrozenStatusesInOrderWithZeroFill() throws Exception {
        when(service.bookingStatuses("2026-08-01", "2026-08-31")).thenReturn(
                new BookingStatusResponse("2026-08-01", "2026-08-31", List.of(
                        agg("PENDING_APPROVAL", 2), agg("CONFIRMED", 0), agg("CHECKED_IN", 0),
                        agg("COMPLETED", 5), agg("REJECTED", 0), agg("CANCELLED", 3),
                        agg("NO_SHOW", 1))));

        mvc.perform(get("/api/v1/admin/statistics/bookings")
                        .param("fromDate", "2026-08-01").param("toDate", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(7))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.records[0].count").value(2))
                .andExpect(jsonPath("$.data.records[3].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.records[5].count").value(3));
    }

    @Test
    void missingOrIllegalDatesFromServiceMapTo40000() throws Exception {
        when(service.resourceUsage(null, "2026-08-07"))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "fromDate is required"));
        when(service.resourceUsage("2026/08/01", "2026-08-07"))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid date"));
        when(service.bookingStatuses("2026-08-27", "2026-08-01"))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid range"));
        mvc.perform(get("/api/v1/admin/statistics/resources")
                        .param("toDate", "2026-08-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/api/v1/admin/statistics/resources")
                        .param("fromDate", "2026/08/01").param("toDate", "2026-08-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/api/v1/admin/statistics/bookings")
                        .param("fromDate", "2026-08-27").param("toDate", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(service).resourceUsage(null, "2026-08-07");
        verify(service).resourceUsage("2026/08/01", "2026-08-07");
        verify(service).bookingStatuses("2026-08-27", "2026-08-01");
    }

    @Test
    void unauthenticatedAndForbiddenPrincipalOutcomesMapToTheirHttpStatuses() throws Exception {
        applyAuthFailure(new BizException(ErrorCode.UNAUTHENTICATED, "unauthenticated"));
        mvc.perform(get("/api/v1/admin/statistics/resources")
                        .param("fromDate", "2026-08-01").param("toDate", "2026-08-07"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        applyAuthFailure(new BizException(ErrorCode.FORBIDDEN, "forbidden"));
        mvc.perform(get("/api/v1/admin/statistics/bookings")
                        .param("fromDate", "2026-08-01").param("toDate", "2026-08-07"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private BookingStatusResponse.BookingStatusAggregate agg(String status, int count) {
        return new BookingStatusResponse.BookingStatusAggregate(status, count);
    }
}
