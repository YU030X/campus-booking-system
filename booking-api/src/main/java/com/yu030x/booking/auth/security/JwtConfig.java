package com.yu030x.booking.auth.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu030x.booking.user.UserMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JwtConfig {
    @Bean
    JwtSettings jwtSettings(@Value("${booking.security.jwt-secret:}") String secret,
            @Value("${JWT_TTL_SECONDS:7200}") long ttlSeconds) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (ttlSeconds < 1 || ttlSeconds > 86400) {
            throw new IllegalStateException("JWT_TTL_SECONDS must be between 1 and 86400");
        }
        return new JwtSettings(new SecretKeySpec(secretBytes, "HmacSHA256"), ttlSeconds);
    }

    @Bean
    Clock jwtClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    JwtEncoder jwtEncoder(JwtSettings settings) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(settings.secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtSettings settings, Clock jwtClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(settings.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestampValidator.setClock(jwtClock);
        decoder.setJwtValidator(timestampValidator);
        return decoder;
    }
}
