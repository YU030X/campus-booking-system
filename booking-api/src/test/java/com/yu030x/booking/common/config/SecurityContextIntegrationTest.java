package com.yu030x.booking.common.config;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.auth.AuthController;
import com.yu030x.booking.auth.AuthService;
import com.yu030x.booking.auth.security.AuthSecurityConfig;
import com.yu030x.booking.auth.security.JwtConfig;
import com.yu030x.booking.user.AdminUserController;
import com.yu030x.booking.user.UserController;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BookingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
                "booking.security.jwt-secret=",
                "booking.identity.enabled=false",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class SecurityContextIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired ApplicationContext context;
    @Autowired Environment environment;

    @Test
    void healthIsPublicAndOtherRoutesAreDenied() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/random", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/api/v1/ping", String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isIn(401, 403, 404);
    }

    @Test
    void doesNotCreateDefaultSecurityBeans() {
        assertThat(context.getBeansOfType(UserMapper.class)).isEmpty();
        assertThat(context.getBeansOfType(UserService.class)).isEmpty();
        assertThat(context.getBeansOfType(UserController.class)).isEmpty();
        assertThat(context.getBeansOfType(AdminUserController.class)).isEmpty();
        assertThat(context.getBeansOfType(JwtConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthSecurityConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthService.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthController.class)).isEmpty();
        assertThat(hasOrderTwoApiChain()).isFalse();
        assertThat(context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.security.oauth2.jwt.JwtDecoder.class)).isEmpty();
    }

    @Test
    void startupDoesNotRequireOrLogSecrets(CapturedOutput output) {
        assertThat(environment.getProperty("booking.security.jwt-secret")).isEmpty();
        assertThat(output.getOut()).doesNotContain("Using generated security password");
        assertThat(output.getOut()).doesNotContain("JWT_SECRET");
        assertThat(output.getOut()).doesNotContain("jwt-secret");
    }

    private boolean hasOrderTwoApiChain() {
        return context.getBeansOfType(SecurityFilterChain.class).keySet().stream()
                .map(name -> context.findAnnotationOnBean(name, Order.class))
                .anyMatch(order -> order != null && order.value() == 2);
    }
}
