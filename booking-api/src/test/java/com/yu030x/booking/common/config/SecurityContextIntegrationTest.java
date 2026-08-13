package com.yu030x.booking.common.config;

import com.yu030x.booking.BookingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BookingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class SecurityContextIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired ApplicationContext context;

    @Test
    void healthIsPublicAndOtherRoutesAreDenied() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/random", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/api/v1/ping", String.class).getStatusCode().value()).isIn(401, 403);
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isIn(401, 403, 404);
    }

    @Test
    void doesNotCreateDefaultSecurityBeans() {
        assertThat(context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.security.oauth2.jwt.JwtDecoder.class)).isEmpty();
    }

    @Test
    void startupDoesNotLogGeneratedPasswordOrSecrets(CapturedOutput output) {
        assertThat(System.getenv("JWT_SECRET")).isNull();
        assertThat(output.getOut()).doesNotContain("Using generated security password");
        assertThat(output.getOut()).doesNotContain("JWT_SECRET");
    }
}
