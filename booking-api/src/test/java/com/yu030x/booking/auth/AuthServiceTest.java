package com.yu030x.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.auth.security.JwtSettings;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

class AuthServiceTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private final Clock clock = Clock.fixed(Instant.now(), ZoneId.of("Asia/Shanghai"));

    @Test
    void registersStudentWithBcrypt12AndReturnsNoCredentialFields() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.id = 7L;
            return 1;
        });
        when(mapper.selectById(7L)).thenAnswer(invocation -> null);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        AuthService service = service(mapper, encoder, 7200);

        var view = service.register(new RegisterRequest("student01", "password8", "Student",
                null, null, null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).insert(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.password).startsWith("$2").contains("$12$");
        assertThat(encoder.matches("password8", saved.password)).isTrue();
        assertThat(saved.role).isEqualTo(UserRole.STUDENT);
        assertThat(saved.creditScore).isEqualTo(100);
        assertThat(saved.status).isEqualTo(1);
        assertThat(view.id()).isEqualTo("7");
    }

    @Test
    void loginUsesConfiguredTtlAndRequiredClaims() {
        UserMapper mapper = mock(UserMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = activeUser(encoder.encode("password8"));
        when(mapper.selectOne(any())).thenReturn(user);
        AuthService service = service(mapper, encoder, 3210);

        LoginResponse response = service.login(new LoginRequest(" student01 ", "password8"));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key()).build();
        var jwt = decoder.decode(response.token());

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3210);
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(3210);
        assertThat(jwt.getSubject()).isEqualTo("5");
        assertThat(jwt.getClaims()).containsAllEntriesOf(Map.of(
                "username", "student01", "role", "STUDENT", "status", 1L));
    }

    @Test
    void nonexistentLoginPerformsDummyHashCheckAndUsesChineseFailure() {
        UserMapper mapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("dummy-hash");
        when(mapper.selectOne(any())).thenReturn(null);
        AuthService service = service(mapper, encoder, 7200);

        assertThatThrownBy(() -> service.login(new LoginRequest("missing", "password8")))
                .isInstanceOf(BizException.class)
                .hasMessage("账号或密码错误");
        verify(encoder).matches("password8", "dummy-hash");
    }

    @Test
    void translatesInsertRaceDuplicateToConflictError() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("uk_username"));
        AuthService service = service(mapper, new BCryptPasswordEncoder(12), 7200);

        assertThatThrownBy(() -> service.register(new RegisterRequest(
                "student01", "password8", "Student", null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.errorCode.code).isEqualTo(41000);
                    assertThat(exception.errorCode.httpStatus).isEqualTo(409);
                    assertThat(exception).hasMessage("username already exists");
                });
    }

    private AuthService service(UserMapper mapper, PasswordEncoder encoder, long ttl) {
        JwtSettings settings = new JwtSettings(key(), ttl);
        NimbusJwtEncoder jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(settings.secretKey()));
        return new AuthService(mapper, encoder, jwtEncoder, settings, clock);
    }

    private SecretKeySpec key() {
        return new SecretKeySpec(SECRET, "HmacSHA256");
    }

    private User activeUser(String password) {
        User user = new User();
        user.id = 5L;
        user.username = "student01";
        user.password = password;
        user.realName = "Student";
        user.role = UserRole.STUDENT;
        user.creditScore = 100;
        user.status = 1;
        user.deleted = 0;
        return user;
    }
}
