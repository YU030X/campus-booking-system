package com.yu030x.booking.checkin;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.checkin.controller.CheckInController;
import com.yu030x.booking.checkin.service.CheckInService;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.user.UserRole;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckInControllerMockMvcTest {
    private CheckInService checkInService;
    private BookingPrincipalAccessor accessor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        checkInService = mock(CheckInService.class);
        accessor = mock(BookingPrincipalAccessor.class);
        when(accessor.current()).thenReturn(new BookingPrincipal(5L, "stu", UserRole.STUDENT));
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        new com.yu030x.booking.common.config.JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new CheckInController(checkInService, accessor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private BookingView view() {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                LocalDateTime.of(2026, 8, 26, 12, 15), LocalDateTime.of(2026, 8, 26, 13, 15),
                null, null, com.yu030x.booking.common.api.BookingStatus.CHECKED_IN,
                LocalDateTime.of(2026, 8, 26, 12, 0, 0), null, null,
                LocalDateTime.of(2026, 8, 26, 10, 0), LocalDateTime.of(2026, 8, 26, 10, 0));
    }

    @Test
    void checkInReturnsCanonicalEnvelopeWithStringIdsAndShanghaiTimestamp() throws Exception {
        when(checkInService.checkIn(5L, 9L)).thenReturn(view());
        mvc.perform(post("/api/v1/bookings/9/check-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.userId").value("5"))
                .andExpect(jsonPath("$.data.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.data.checkinTime").value("2026-08-26 12:00:00"));
    }

    @Test
    void foreignMissingAndDeletedBookingsShareTheIdentical404Masking() throws Exception {
        for (String id : new String[]{"404", "999999", "12345"}) {
            when(checkInService.checkIn(5L, Long.parseLong(id)))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "booking not found"));
            mvc.perform(post("/api/v1/bookings/" + id + "/check-in"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(40400))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void wrongStatusOrOutsideWindowReturns409WithBookingError() throws Exception {
        when(checkInService.checkIn(5L, 9L))
                .thenThrow(new BizException(ErrorCode.BOOKING_ERROR, "booking is not check-in eligible"));
        mvc.perform(post("/api/v1/bookings/9/check-in"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(43000))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void nonDecimalBookingIdIsRejectedAsInvalidParameter() throws Exception {
        mvc.perform(post("/api/v1/bookings/not-a-number/check-in"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void missingAuthenticationSurfacesShared401Contract() throws Exception {
        when(accessor.current()).thenThrow(
                new BizException(ErrorCode.UNAUTHENTICATED, "unauthenticated"));
        mvc.perform(post("/api/v1/bookings/9/check-in"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(content().json(
                        "{\"code\":40100,\"message\":\"unauthenticated\",\"data\":null}", true));
    }
}
