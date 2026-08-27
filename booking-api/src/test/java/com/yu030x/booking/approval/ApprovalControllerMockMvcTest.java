package com.yu030x.booking.approval;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.approval.controller.ApprovalAdminController;
import com.yu030x.booking.approval.controller.BookingCancelController;
import com.yu030x.booking.approval.service.ApprovalService;
import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalControllerMockMvcTest {
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private ApprovalService approvalService;
    private BookingPrincipalAccessor accessor;
    private MockMvc adminMvc;
    private MockMvc cancelMvc;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        new com.yu030x.booking.common.config.JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(mapper);

        BookingPrincipalAccessor adminAccessor = mock(BookingPrincipalAccessor.class);
        when(adminAccessor.current()).thenReturn(new BookingPrincipal(3L, "adm", UserRole.ADMIN));
        adminMvc = MockMvcBuilders.standaloneSetup(
                        new ApprovalAdminController(approvalService, adminAccessor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();

        accessor = mock(BookingPrincipalAccessor.class);
        when(accessor.current()).thenReturn(new BookingPrincipal(5L, "stu", UserRole.STUDENT));
        cancelMvc = MockMvcBuilders.standaloneSetup(
                        new BookingCancelController(approvalService, accessor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private BookingView view(BookingStatus status) {
        return new BookingView("9", "BK20260826120000ABC123", "5", "7",
                LocalDateTime.of(2026, 8, 26, 15, 0), LocalDateTime.of(2026, 8, 26, 16, 0),
                null, null, status, null,
                status == BookingStatus.CANCELLED ? LocalDateTime.of(2026, 8, 26, 12, 0) : null,
                status == BookingStatus.CANCELLED ? "行程有变" : null,
                LocalDateTime.of(2026, 8, 26, 10, 0), LocalDateTime.of(2026, 8, 26, 12, 0));
    }

    @Test
    void pendingListReturnsCanonicalPageWithStringIdsInServerOrder() throws Exception {
        when(approvalService.pendingPage(1, 100)).thenReturn(new PageResult<>(
                1, 100, 2L, List.of(view(BookingStatus.PENDING_APPROVAL))));

        adminMvc.perform(get("/api/v1/admin/approvals").param("pageNumber", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pageNumber").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("9"))
                .andExpect(jsonPath("$.data.records[0].userId").value("5"))
                .andExpect(jsonPath("$.data.records[0].resourceId").value("7"))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.records[0].createdAt")
                        .value("2026-08-26 10:00:00"));
    }

    @Test
    void pendingPageSizeOutsideBoundsIsRejectedWith40000() throws Exception {
        when(approvalService.pendingPage(anyInt(), anyInt()))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid page bounds"));
        for (String pageSize : new String[]{"0", "101", "-1"}) {
            adminMvc.perform(get("/api/v1/admin/approvals").param("pageSize", pageSize))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void approveAcceptsBlankCommentAsNullNormalizedSuccess() throws Exception {
        when(approvalService.approve(9L, 3L, null)).thenReturn(view(BookingStatus.CONFIRMED));

        adminMvc.perform(post("/api/v1/admin/bookings/9/approve").contentType(JSON)
                        .content("{\"comment\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void approveUnknownFieldReturns40000WithoutStateChange() throws Exception {
        adminMvc.perform(post("/api/v1/admin/bookings/9/approve").contentType(JSON)
                        .content("{\"comment\":\"ok\",\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(approvalService, never()).approve(any(long.class), any(long.class), any());
    }

    @Test
    void rejectRequiresNonBlankCommentWithinFiveHundredCodePoints() throws Exception {
        when(approvalService.reject(9L, 3L, "不符合使用规范")).thenReturn(view(BookingStatus.REJECTED));

        adminMvc.perform(post("/api/v1/admin/bookings/9/reject").contentType(JSON)
                        .content("{\"comment\":\" 不符合使用规范 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        for (String body : new String[]{"{}", "{\"comment\":null}", "{\"comment\":\"   \"}"}) {
            adminMvc.perform(post("/api/v1/admin/bookings/9/reject").contentType(JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        adminMvc.perform(post("/api/v1/admin/bookings/9/reject").contentType(JSON)
                        .content("{\"comment\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        when(approvalService.reject(9L, 3L, "a".repeat(500)))
                .thenReturn(view(BookingStatus.REJECTED));
        adminMvc.perform(post("/api/v1/admin/bookings/9/reject").contentType(JSON)
                        .content("{\"comment\":\"" + "a".repeat(500) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectIllegalTransitionSurfaces409With43000() throws Exception {
        when(approvalService.reject(9L, 3L, "材料不全")).thenThrow(
                new BizException(ErrorCode.BOOKING_ERROR, "booking is not pending approval"));

        adminMvc.perform(post("/api/v1/admin/bookings/9/reject").contentType(JSON)
                        .content("{\"comment\":\"材料不全\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(43000))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void cancelReturnsCurrentViewForWinnerAndDuplicateIdenticalActions() throws Exception {
        when(approvalService.cancel(5L, 9L, "行程有变"))
                .thenReturn(view(BookingStatus.CANCELLED))
                .thenReturn(view(BookingStatus.CANCELLED));

        for (int i = 0; i < 2; i++) {
            cancelMvc.perform(post("/api/v1/bookings/9/cancel").contentType(JSON)
                            .content("{\"cancelReason\":\"行程有变\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value("9"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.cancelReason").value("行程有变"));
        }
    }

    @Test
    void cancelForeignMissingAndDeletedShareIdentical404Masking() throws Exception {
        when(approvalService.cancel(5L, 999999L, null))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "booking not found"));
        cancelMvc.perform(post("/api/v1/bookings/999999/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").value(nullValue()));

        when(approvalService.cancel(5L, 12345L, null))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "booking not found"));
        cancelMvc.perform(post("/api/v1/bookings/12345/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void cancelAtOrAfterStartOrOppositeTerminalActionReturns40943000() throws Exception {
        when(approvalService.cancel(5L, 9L, null)).thenThrow(
                new BizException(ErrorCode.BOOKING_ERROR, "booking cannot be cancelled"));

        cancelMvc.perform(post("/api/v1/bookings/9/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(43000))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void cancelUnknownFieldIsRejectedAs40000() throws Exception {
        cancelMvc.perform(post("/api/v1/bookings/9/cancel").contentType(JSON)
                        .content("{\"cancelReason\":\"ok\",\"status\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void nonDecimalBookingIdsAreRejectedAs40000OnAllRoutes() throws Exception {
        cancelMvc.perform(post("/api/v1/bookings/not-a-number/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        adminMvc.perform(post("/api/v1/admin/bookings/x/approve").contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        adminMvc.perform(post("/api/v1/admin/bookings/x/reject")
                        .contentType(JSON).content("{\"comment\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void missingAuthenticationSurfacesShared401Contract() throws Exception {
        when(accessor.current()).thenThrow(
                new BizException(ErrorCode.UNAUTHENTICATED, "unauthenticated"));

        cancelMvc.perform(post("/api/v1/bookings/9/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":40100,\"message\":\"unauthenticated\",\"data\":null}", true));
    }

    @Test
    void malformedJsonBodiesMapTo40000OnAllRoutes() throws Exception {
        cancelMvc.perform(post("/api/v1/bookings/9/cancel")
                        .contentType(JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        adminMvc.perform(post("/api/v1/admin/bookings/9/approve")
                        .contentType(JSON).content("[1,2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        adminMvc.perform(post("/api/v1/admin/bookings/9/reject")
                        .contentType(JSON).content("plain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
