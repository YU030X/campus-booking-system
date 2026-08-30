package com.yu030x.booking.notification;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.yu030x.booking.notification.controller.NotificationController;
import com.yu030x.booking.notification.service.NotificationService;
import com.yu030x.booking.notification.vo.NotificationView;
import com.yu030x.booking.user.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerMockMvcTest {
    private NotificationService service;
    private BookingPrincipalAccessor accessor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        accessor = mock(BookingPrincipalAccessor.class);
        when(accessor.current()).thenReturn(new BookingPrincipal(5L, "stu", UserRole.STUDENT));
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        new com.yu030x.booking.common.config.JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service, accessor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void listUsesCanonicalEnvelopeWithLongAsStringAndCanonicalTimestamp() throws Exception {
        when(service.pageForCurrentUser(5L, 1, 10)).thenReturn(new PageResult<>(1, 10, 1,
                List.of(new NotificationView(7L, 5L, "预约已通过", "内容", "BOOKING_APPROVED",
                        null, 0, LocalDateTime.of(2026, 8, 27, 9, 5, 0)))));

        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.pageNumber").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("7"))
                .andExpect(jsonPath("$.data.records[0].userId").value("5"))
                .andExpect(jsonPath("$.data.records[0].bizId").value(nullValue()))
                .andExpect(jsonPath("$.data.records[0].isRead").value(0))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-08-27 09:05:00"));
    }

    @Test
    void invalidPageBoundsReturn40000WithoutData() throws Exception {
        when(service.pageForCurrentUser(5L, 0, 10))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));
        when(service.pageForCurrentUser(5L, 1, 101))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));

        mvc.perform(get("/api/v1/notifications").param("pageNumber", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/api/v1/notifications").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void ownerReadReturnsSuccessEnvelopeAndDelegatesCurrentUserId() throws Exception {
        mvc.perform(post("/api/v1/notifications/7/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).markReadForCurrentUser(5L, 7L);
    }

    @Test
    void nonNumericZeroNegativeAndOverflowIdsAre40000WithoutTouchingTheService() throws Exception {
        mvc.perform(post("/api/v1/notifications/abc/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/notifications/0/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/notifications/-7/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/notifications/+5/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/notifications/99999999999999999999999/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/notifications/%20/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(service, never()).markReadForCurrentUser(anyLong(), anyLong());
    }

    @Test
    void foreignIdIsMappedTo404WithUnifiedMessage() throws Exception {
        doThrow(new BizException(ErrorCode.NOT_FOUND, "notification not found"))
                .when(service).markReadForCurrentUser(5L, 77L);

        mvc.perform(post("/api/v1/notifications/77/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("notification not found"));
    }
}
