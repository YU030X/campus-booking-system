package com.yu030x.booking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserRole;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@SpringBootTest(classes = {BookingApplication.class, AuthSecurityChainIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
                "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
class AuthSecurityChainIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    void actualApiChainReturnsExact401ForMissingBearer() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/users/me", String.class);

        assertError(response, 401, 40100, "unauthenticated");
    }

    @Test
    void actualApiChainReturnsExact403WhenStudentCallsAdminEndpoint() throws Exception {
        User student = new User();
        student.id = 5L;
        student.username = "student01";
        student.role = UserRole.STUDENT;
        student.status = 1;
        student.deleted = 0;
        when(userMapper.selectById(5L)).thenReturn(student);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken());
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertError(response, 403, 40300, "forbidden");
    }

    private String studentToken() {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("5")
                .claim("username", "student01")
                .claim("role", "STUDENT")
                .claim("status", 1)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(7200))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void assertError(ResponseEntity<String> response, int status, int code, String message)
            throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(code);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.has("data")).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        UserMapper userMapper() {
            return mock(UserMapper.class);
        }

        @Bean
        Clock jwtClock() {
            return Clock.system(ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        JwtSettings jwtSettings() {
            return new JwtConfig().jwtSettings("0123456789abcdef0123456789abcdef", 7200);
        }

        @Bean
        JwtEncoder jwtEncoder(JwtSettings settings) {
            return new JwtConfig().jwtEncoder(settings);
        }

        @Bean
        JwtDecoder jwtDecoder(JwtSettings settings, Clock jwtClock) {
            return new JwtConfig().jwtDecoder(settings, jwtClock);
        }

        @Bean
        BookingPrincipalAccessor bookingPrincipalAccessor() {
            return new BookingPrincipalAccessor();
        }

        @Bean
        @Order(2)
        SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
                UserMapper userMapper, ObjectMapper objectMapper, Clock jwtClock) throws Exception {
            return new AuthSecurityConfig().apiSecurity(
                    http, jwtDecoder, userMapper, objectMapper, jwtClock);
        }
    }
}
