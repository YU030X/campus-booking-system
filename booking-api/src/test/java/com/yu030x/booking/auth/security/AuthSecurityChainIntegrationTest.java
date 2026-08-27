package com.yu030x.booking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.auth.AuthController;
import com.yu030x.booking.auth.AuthService;
import com.yu030x.booking.user.AdminUserController;
import com.yu030x.booking.user.UserController;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootTest(classes = BookingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
                "booking.security.jwt-secret=",
                "booking.identity.enabled=false",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
class AuthSecurityChainIntegrationTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private ApplicationContext context;

    @Test
    void disabledIdentityBoundaryIsPublicHealthAndDenyAllApi() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/api/v1/users/me", String.class).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void disabledIdentityBoundaryDoesNotRegisterIdentityFixtureOrProductionBeans() {
        assertThat(context.getBeansOfType(UserMapper.class)).isEmpty();
        assertThat(context.getBeansOfType(UserService.class)).isEmpty();
        assertThat(context.getBeansOfType(UserController.class)).isEmpty();
        assertThat(context.getBeansOfType(AdminUserController.class)).isEmpty();
        assertThat(context.getBeansOfType(JwtConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthSecurityConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthService.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthController.class)).isEmpty();
        assertThat(hasOrderTwoApiChain()).isFalse();
    }

    private boolean hasOrderTwoApiChain() {
        return context.getBeansOfType(SecurityFilterChain.class).keySet().stream()
                .map(name -> context.findAnnotationOnBean(name, Order.class))
                .anyMatch(order -> order != null && order.value() == 2);
    }
}
