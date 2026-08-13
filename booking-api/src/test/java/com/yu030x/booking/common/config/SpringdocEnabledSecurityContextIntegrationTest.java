package com.yu030x.booking.common.config;

import com.yu030x.booking.BookingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BookingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
                "booking.security.jwt-secret=",
                "SPRINGDOC_ENABLED=true"
        })
class SpringdocEnabledSecurityContextIntegrationTest {
    @Autowired TestRestTemplate rest;

    @Test
    void enabledSpringdocEndpointsRemainDeniedByFoundationSecurity() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/v3/api-docs/swagger-config", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/swagger-ui.html", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value()).isIn(401, 403);
    }
}
