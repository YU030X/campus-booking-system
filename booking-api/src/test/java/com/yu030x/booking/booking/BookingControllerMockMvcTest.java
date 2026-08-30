package com.yu030x.booking.booking;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.controller.BookingController;
import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.service.BookingService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.user.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class BookingControllerMockMvcTest {
    private BookingService bookingService;
    private BookingPrincipalAccessor accessor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bookingService = mock(BookingService.class);
        accessor = mock(BookingPrincipalAccessor.class);
        when(accessor.current()).thenReturn(new BookingPrincipal(5L, "stu", UserRole.STUDENT));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new BookingController(bookingService, accessor))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private BookingView view() {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                LocalDateTime.of(2026, 8, 26, 14, 0), LocalDateTime.of(2026, 8, 26, 15, 0),
                "讨论", 2, null, null, null, null,
                LocalDateTime.of(2026, 8, 26, 10, 0), LocalDateTime.of(2026, 8, 26, 10, 0));
    }

    @Test
    void createReturns201WithCanonicalEnvelopeAndStringIds() throws Exception {
        when(bookingService.create(any(), any(CreateBookingRequest.class))).thenReturn(view());
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"7\",\"startTime\":\"2026-08-27 14:00:00\","
                                + "\"endTime\":\"2026-08-27 15:00:00\",\"purpose\":\" 讨论 \","
                                + "\"attendeeCount\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.userId").value("5"))
                .andExpect(jsonPath("$.data.resourceId").value("7"));
    }

    @Test
    void createRejectsUnknownFieldsAndMalformedBodiesAs40000() throws Exception {
        assertInvalid(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"7\",\"startTime\":\"2026-08-27 14:00:00\","
                        + "\"endTime\":\"2026-08-27 15:00:00\",\"attendeeCount\":2,\"extra\":true}"));
        assertInvalid(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"abc\",\"startTime\":\"bad\","
                        + "\"endTime\":\"2026-08-27 15:00:00\",\"attendeeCount\":0}"));
    }

    @Test
    void listUsesPageBoundsAndOptionalStatusFiltering() throws Exception {
        when(bookingService.list(eq(5L), eq(2), eq(20), eq(com.yu030x.booking.common.api.BookingStatus.CONFIRMED)))
                .thenReturn(new PageResult<>(2, 20, 1, List.of(view())));
        mvc.perform(get("/api/v1/bookings")
                        .param("pageNumber", "2").param("pageSize", "20").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pageNumber").value(2))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].bookingNo").value("BK20260826120000ABC123"));
        verify(bookingService).list(eq(5L), eq(2), eq(20),
                eq(com.yu030x.booking.common.api.BookingStatus.CONFIRMED));
    }

    @Test
    void listRejectsBadPageBoundsAndStatuses() throws Exception {
        when(bookingService.list(eq(5L), eq(0), eq(10), any()))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid page bounds"));
        when(bookingService.list(eq(5L), eq(1), eq(101), any()))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid page bounds"));
        assertInvalid(get("/api/v1/bookings").param("pageNumber", "0"));
        assertInvalid(get("/api/v1/bookings").param("pageSize", "101"));
        assertInvalid(get("/api/v1/bookings").param("status", "MAYBE"));
    }

    @Test
    void detailEndpointsUseExactEnvelopes() throws Exception {
        when(bookingService.detail(5L, 9L)).thenReturn(view());
        mvc.perform(get("/api/v1/bookings/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.status").value(nullValue()));
        when(bookingService.detail(5L, 404L))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "booking not found"));
        mvc.perform(get("/api/v1/bookings/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void detailRejectsNonDecimalIds() throws Exception {
        assertInvalid(get("/api/v1/bookings/not-a-number"));
    }

    private void assertInvalid(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
