package com.yu030x.booking.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.common.config.JacksonConfig;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.user.UserRole;
import com.yu030x.booking.user.UserView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerMockMvcTest {
    private AuthService authService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder().modules(new JavaTimeModule());
        new JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void registerReturnsExact201EnvelopeWithoutTokenOrInternalFields() throws Exception {
        when(authService.register(any())).thenReturn(view());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student01","password":"password8","realName":"Student"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void loginReturnsTokenContract() throws Exception {
        when(authService.login(any())).thenReturn(new LoginResponse("a.b.c", "Bearer", 7200, view()));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student01\",\"password\":\"password8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("a.b.c"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    void duplicateUsernameUsesExact409Envelope() throws Exception {
        when(authService.register(any()))
                .thenThrow(new BizException(ErrorCode.USER_ERROR, "username already exists"));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student01","password":"password8","realName":"Student"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(41000))
                .andExpect(jsonPath("$.message").value("username already exists"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidLoginUsesChineseFailureInsteadOfBearerMessage() throws Exception {
        when(authService.login(any()))
                .thenThrow(new BizException(ErrorCode.UNAUTHENTICATED, "账号或密码错误"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student01\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("账号或密码错误"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().json(
                        "{\"code\":40100,\"message\":\"账号或密码错误\",\"data\":null}", true));
    }

    @Test
    void registerAndLoginRejectUnknownJsonWithExactError() throws Exception {
        assertUnknown("/api/v1/auth/register",
                "{\"username\":\"student01\",\"password\":\"password8\",\"realName\":\"S\",\"role\":\"ADMIN\"}");
        assertUnknown("/api/v1/auth/login",
                "{\"username\":\"student01\",\"password\":\"password8\",\"extra\":true}");
    }

    private void assertUnknown(String path, String json) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("invalid parameter"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().json(
                        "{\"code\":40000,\"message\":\"invalid parameter\",\"data\":null}", true));
    }

    private UserView view() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 13, 16, 0);
        return new UserView("9", "student01", "Student", null, null, null, null,
                UserRole.STUDENT, 100, 1, time, time);
    }
}
