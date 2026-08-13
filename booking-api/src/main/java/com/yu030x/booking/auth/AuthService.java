package com.yu030x.booking.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yu030x.booking.auth.security.JwtSettings;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserRole;
import com.yu030x.booking.user.UserStatus;
import com.yu030x.booking.user.UserView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(UserMapper.class)
public class AuthService {
    private static final String LOGIN_FAILED = "账号或密码错误";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtSettings jwtSettings;
    private final Clock clock;
    private final String dummyPasswordHash;

    AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            JwtSettings jwtSettings, Clock jwtClock) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtSettings = jwtSettings;
        this.clock = jwtClock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-never-used");
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        if (activeUsernameExists(request.username())) {
            throw new BizException(ErrorCode.USER_ERROR, "username already exists");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        user.username = request.username();
        user.password = passwordEncoder.encode(request.password());
        user.realName = request.realName();
        user.studentNo = request.studentNo();
        user.phone = request.phone();
        user.email = request.email();
        user.avatar = request.avatar();
        user.role = UserRole.STUDENT;
        user.creditScore = 100;
        user.status = UserStatus.ENABLED.value();
        user.deleted = 0;
        user.createdAt = now;
        user.updatedAt = now;
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.USER_ERROR, "username already exists");
        }
        User persisted = userMapper.selectById(user.id);
        return UserView.from(persisted == null ? user : persisted);
    }

    public LoginResponse login(LoginRequest request) {
        User user = findActiveByUsername(request.username());
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw loginFailure();
        }
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.password);
        if (!passwordMatches || !UserStatus.ENABLED.matches(user.status)) {
            throw loginFailure();
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(jwtSettings.ttlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.id.toString())
                .claim("username", user.username)
                .claim("role", user.role.name())
                .claim("status", user.status)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                UserView.from(user));
    }

    private boolean activeUsernameExists(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0;
    }

    private User findActiveByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private BizException loginFailure() {
        return new BizException(ErrorCode.UNAUTHENTICATED, LOGIN_FAILED);
    }
}
