package com.yu030x.booking.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu030x.booking.auth.security.JwtSettings;
import com.yu030x.booking.user.UserMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class AuthServiceTestFactory {
    private AuthServiceTestFactory() {
    }

    public static AuthService create(UserMapper userMapper, Clock clock) {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        JwtSettings settings = new JwtSettings(new SecretKeySpec(secret, "HmacSHA256"), 7200);
        return new AuthService(userMapper, new BCryptPasswordEncoder(12),
                new NimbusJwtEncoder(new ImmutableSecret<>(settings.secretKey())), settings, clock);
    }
}
