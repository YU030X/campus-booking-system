package com.yu030x.booking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

class JwtSecurityTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Instant now = Instant.parse("2026-08-13T08:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"));
    private final JwtConfig config = new JwtConfig();
    private JwtSettings settings;
    private JwtEncoder encoder;
    private UserMapper userMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        settings = config.jwtSettings(SECRET, 7200);
        encoder = config.jwtEncoder(settings);
        userMapper = mock(UserMapper.class);
        filter = new JwtAuthenticationFilter(config.jwtDecoder(settings, clock), userMapper,
                new JsonAuthenticationEntryPoint(new ObjectMapper()), clock);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validatesSecretAndTtlBoundsWithoutExposingSecret() {
        assertThatThrownBy(() -> config.jwtSettings("short", 7200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT_SECRET must contain at least 32 UTF-8 bytes")
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> config.jwtSettings(SECRET, 0)).hasMessageContaining("between 1 and 86400");
        assertThatThrownBy(() -> config.jwtSettings(SECRET, 86401)).hasMessageContaining("between 1 and 86400");
        assertThat(config.jwtSettings(SECRET, 1).ttlSeconds()).isEqualTo(1);
        assertThat(config.jwtSettings(SECRET, 86400).ttlSeconds()).isEqualTo(86400);
    }

    @Test
    void authenticatesHs256ClaimsAndPrincipalAccessor() throws Exception {
        User user = activeUser();
        when(userMapper.selectById(5L)).thenReturn(user);
        MockHttpServletResponse response = invoke("Bearer " + token(now, now.plusSeconds(7200)), false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
        assertThat(new BookingPrincipalAccessor().current())
                .isEqualTo(new BookingPrincipal(5L, "student01", UserRole.STUDENT));
    }

    @Test
    void rejectsMalformedTamperedDuplicatedAndOtherSchemeHeaders() throws Exception {
        String token = token(now, now.plusSeconds(7200));
        assertUnauthenticated("Basic " + token, false);
        assertUnauthenticated("Bearer", false);
        assertUnauthenticated("Bearer  " + token, false);
        assertUnauthenticated("Bearer a.b.c", false);
        assertUnauthenticated("Bearer " + token + ",other", false);
        assertUnauthenticated("Bearer " + token.substring(0, token.length() - 1) + "x", false);
        assertUnauthenticated("Bearer " + token, true);
    }

    @Test
    void rejectsEveryMissingRequiredClaim() throws Exception {
        for (String missing : new String[] {"sub", "username", "role", "status", "iat", "exp"}) {
            assertUnauthenticated("Bearer " + tokenWithout(missing), false);
        }
    }

    @Test
    void appliesThirtySecondClockSkewAndRejectsFutureIat() throws Exception {
        when(userMapper.selectById(5L)).thenReturn(activeUser());
        assertThat(invoke("Bearer " + token(now.minusSeconds(3600), now.minusSeconds(20)), false).getStatus())
                .isEqualTo(200);
        assertUnauthenticated("Bearer " + token(now.minusSeconds(3600), now.minusSeconds(31)), false);
        assertUnauthenticated("Bearer " + token(now.plusSeconds(31), now.plusSeconds(3600)), false);
    }

    @Test
    void rejectsOldTokenAfterUserIsDisabledOrLogicallyDeleted() throws Exception {
        User disabled = activeUser();
        disabled.status = 0;
        when(userMapper.selectById(5L)).thenReturn(disabled);
        assertUnauthenticated("Bearer " + token(now, now.plusSeconds(7200)), false);

        when(userMapper.selectById(5L)).thenReturn(null);
        assertUnauthenticated("Bearer " + token(now, now.plusSeconds(7200)), false);
    }

    private void assertUnauthenticated(String header, boolean duplicate) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = invoke(header, duplicate);
        assertThat(response.getStatus()).isEqualTo(401);
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(40100);
        assertThat(body.path("message").asText()).isEqualTo("unauthenticated");
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletResponse invoke(String header, boolean duplicate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", header);
        if (duplicate) {
            request.addHeader("Authorization", header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("5")
                .claim("username", "student01")
                .claim("role", "STUDENT")
                .claim("status", 1)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String tokenWithout(String missing) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
        if (!"sub".equals(missing)) {
            claims.subject("5");
        }
        if (!"username".equals(missing)) {
            claims.claim("username", "student01");
        }
        if (!"role".equals(missing)) {
            claims.claim("role", "STUDENT");
        }
        if (!"status".equals(missing)) {
            claims.claim("status", 1);
        }
        if (!"iat".equals(missing)) {
            claims.issuedAt(now);
        }
        if (!"exp".equals(missing)) {
            claims.expiresAt(now.plusSeconds(7200));
        }
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private User activeUser() {
        User user = new User();
        user.id = 5L;
        user.username = "student01";
        user.role = UserRole.STUDENT;
        user.status = 1;
        user.deleted = 0;
        return user;
    }
}
