package com.yu030x.booking.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.auth.AuthController;
import com.yu030x.booking.auth.AuthService;
import com.yu030x.booking.auth.security.AuthSecurityConfig;
import com.yu030x.booking.auth.security.JwtConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootTest(classes = BookingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"booking.identity.enabled=true",
                "booking.security.jwt-secret=0123456789abcdef0123456789abcdef"})
class UserMapperRegistrationIntegrationTest {
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private String username;

    @AfterEach
    void cleanup() {
        if (username != null) {
            jdbcTemplate.update("DELETE FROM `user` WHERE `username` = ?", username);
        }
    }

    @Test
    void productionMapperAndAuthChainServeCanonicalBoundaries() {
        assertThat(applicationContext.getBeansOfType(UserMapper.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(UserService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(UserController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AdminUserController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(JwtConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AuthSecurityConfig.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AuthService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(hasOrderTwoApiChain()).isTrue();

        username = "t02_http_" + UUID.randomUUID().toString().replace("-", "");
        String registerJson = "{\"username\":\"" + username
                + "\",\"password\":\"password8\",\"realName\":\"HTTP User\"}";
        ResponseEntity<String> register = post("/api/v1/auth/register", registerJson, null);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> login = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"password8\"}", null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).contains("\"tokenType\":\"Bearer\"");
        String token = login.getBody().replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(token).isNotBlank();

        ResponseEntity<String> anonymous = get("/api/v1/users/me", null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).contains("\"code\":40100", "\"message\":\"unauthenticated\"");

        ResponseEntity<String> studentAdmin = get("/api/v1/admin/users", token);
        assertThat(studentAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(studentAdmin.getBody()).contains("\"code\":40300", "\"message\":\"forbidden\"");
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = headers(token);
        return restTemplate.postForEntity("http://localhost:" + port + path,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return restTemplate.exchange("http://localhost:" + port + path,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private boolean hasOrderTwoApiChain() {
        return applicationContext.getBeansOfType(SecurityFilterChain.class).keySet().stream()
                .map(name -> applicationContext.findAnnotationOnBean(name, Order.class))
                .anyMatch(order -> order != null && order.value() == 2);
    }
}
