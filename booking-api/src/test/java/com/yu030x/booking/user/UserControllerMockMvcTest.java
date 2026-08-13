package com.yu030x.booking.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

class UserControllerMockMvcTest {
    private UserService userService;
    private BookingPrincipalAccessor accessor;
    private BookingPrincipal principal;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        accessor = mock(BookingPrincipalAccessor.class);
        principal = new BookingPrincipal(1L, "admin", UserRole.ADMIN);
        when(accessor.current()).thenReturn(principal);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MethodValidationPostProcessor methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.setProxyTargetClass(true);
        methodValidation.afterPropertiesSet();
        AdminUserController adminController = (AdminUserController) methodValidation
                .postProcessAfterInitialization(
                        new AdminUserController(userService, accessor), "adminUserController");
        mvc = MockMvcBuilders.standaloneSetup(
                        new UserController(userService, accessor),
                        adminController)
                .setValidator(validator)
                .setControllerAdvice(new UserExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getAndPutMeUseExactResultEnvelope() throws Exception {
        when(userService.currentUser(principal)).thenReturn(view(1, UserRole.ADMIN, 1));
        when(userService.replaceProfile(eq(principal), any())).thenReturn(view(1, UserRole.ADMIN, 1));
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("1"));
        mvc.perform(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"Admin\",\"phone\":null,\"email\":null,\"avatar\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void putAndPatchRejectUnknownOrProtectedFields() throws Exception {
        assertInvalid(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"realName\":\"Admin\",\"role\":\"STUDENT\"}"));
        assertInvalid(patch("/api/v1/admin/users/2/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0,\"deleted\":1}"));
    }

    @Test
    void adminListIgnoresExtraQueryAndPassesCanonicalFilters() throws Exception {
        when(userService.listUsers(2, 20, "key", UserRole.STUDENT, 1))
                .thenReturn(new PageResult<>(2, 20, 1, List.of(view(2, UserRole.STUDENT, 1))));
        mvc.perform(get("/api/v1/admin/users")
                        .param("pageNumber", "2").param("pageSize", "20")
                        .param("keyword", "key").param("role", "STUDENT").param("status", "1")
                        .param("undeclared", "ignored"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNumber").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("2"));
        verify(userService).listUsers(2, 20, "key", UserRole.STUDENT, 1);
    }

    @Test
    void adminPageBoundsAndRoleTypeReturnExact400() throws Exception {
        when(userService.listUsers(1, 10, null, null, 2))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));
        when(userService.listUsers(1, 10, "x".repeat(101), null, null))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));
        assertInvalid(get("/api/v1/admin/users").param("pageNumber", "0"));
        assertInvalid(get("/api/v1/admin/users").param("pageSize", "0"));
        assertInvalid(get("/api/v1/admin/users").param("pageSize", "101"));
        assertInvalid(get("/api/v1/admin/users").param("role", "OWNER"));
        assertInvalid(get("/api/v1/admin/users").param("status", "2"));
        assertInvalid(get("/api/v1/admin/users").param("keyword", "x".repeat(101)));
    }

    private void assertInvalid(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("invalid parameter"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().json(
                        "{\"code\":40000,\"message\":\"invalid parameter\",\"data\":null}", true));
    }

    private UserView view(long id, UserRole role, int status) {
        return new UserView(Long.toString(id), "user" + id, "User", null, null, null, null,
                role, 100, status, null, null);
    }
}
