package com.yu030x.booking.violation;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.user.UserRole;
import com.yu030x.booking.violation.controller.ViolationController;
import com.yu030x.booking.violation.service.ViolationService;
import com.yu030x.booking.violation.vo.ViolationView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ViolationControllerMockMvcTest {
    private ViolationService violationService;
    private BookingPrincipalAccessor accessor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        violationService = mock(ViolationService.class);
        accessor = mock(BookingPrincipalAccessor.class);
        when(accessor.current()).thenReturn(new BookingPrincipal(5L, "stu", UserRole.STUDENT));
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        new com.yu030x.booking.common.config.JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new ViolationController(violationService, accessor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private ViolationView view(long id) {
        return new ViolationView(id, 5L, 9L + id, "NO_SHOW", -10, null,
                LocalDateTime.of(2026, 8, 26, 12, 0, 0));
    }

    @Test
    void pageUsesCanonicalEnvelopeWithLongAsStringAndShanghaiTimestamps() throws Exception {
        when(violationService.pageForCurrentUser(5L, 1, 10))
                .thenReturn(new PageResult<>(1, 10, 2, List.of(view(1), view(2))));

        mvc.perform(get("/api/v1/users/me/violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.pageNumber").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].id").value("1"))
                .andExpect(jsonPath("$.data.records[0].userId").value("5"))
                .andExpect(jsonPath("$.data.records[0].bookingId").value("10"))
                .andExpect(jsonPath("$.data.records[0].scoreChange").value(-10))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-08-26 12:00:00"));
    }

    @Test
    void invalidPageBoundsReturn40000WithoutData() throws Exception {
        when(violationService.pageForCurrentUser(5L, 0, 10))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));
        when(violationService.pageForCurrentUser(5L, 1, 101))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));

        mvc.perform(get("/api/v1/users/me/violations").param("pageNumber", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/api/v1/users/me/violations").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void emptyHistoryReturnsCanonicalEmptyPage() throws Exception {
        when(violationService.pageForCurrentUser(5L, 1, 10))
                .thenReturn(new PageResult<>(1, 10, 0, List.of()));

        mvc.perform(get("/api/v1/users/me/violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records", hasSize(0)));
    }
}
