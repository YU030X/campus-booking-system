package com.yu030x.booking.auth.security;

import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import com.yu030x.booking.user.UserRole;
import com.yu030x.booking.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;

final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern BEARER = Pattern.compile(
            "^Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)$");

    private final JwtDecoder jwtDecoder;
    private final UserMapper userMapper;
    private final JsonAuthenticationEntryPoint entryPoint;
    private final Clock clock;

    JwtAuthenticationFilter(JwtDecoder jwtDecoder, UserMapper userMapper,
            JsonAuthenticationEntryPoint entryPoint, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.userMapper = userMapper;
        this.entryPoint = entryPoint;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            if (headers.size() != 1) {
                throw new BadCredentialsException("invalid authorization header");
            }
            var matcher = BEARER.matcher(headers.get(0));
            if (!matcher.matches()) {
                throw new BadCredentialsException("invalid authorization header");
            }
            Jwt jwt = jwtDecoder.decode(matcher.group(1));
            BookingPrincipal principal = validateAndReload(jwt);
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
            var authentication = new UsernamePasswordAuthenticationToken(principal, jwt.getTokenValue(), authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("unauthenticated", exception));
        }
    }

    private BookingPrincipal validateAndReload(Jwt jwt) {
        if (!"HS256".equals(jwt.getHeaders().get("alg"))) {
            throw new BadCredentialsException("invalid algorithm");
        }
        String subject = jwt.getSubject();
        String username = jwt.getClaimAsString("username");
        String roleClaim = jwt.getClaimAsString("role");
        Object statusValue = jwt.getClaim("status");
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (subject == null || username == null || username.isBlank() || roleClaim == null
                || !(statusValue instanceof Number statusNumber) || issuedAt == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt) || issuedAt.isAfter(clock.instant().plusSeconds(30))) {
            throw new BadCredentialsException("missing required claim");
        }
        long id;
        UserRole role;
        try {
            id = Long.parseLong(subject);
            role = UserRole.valueOf(roleClaim);
        } catch (RuntimeException exception) {
            throw new BadCredentialsException("invalid claim", exception);
        }
        int statusClaim = statusNumber.intValue();
        if (id < 1 || statusNumber.longValue() != statusClaim || !UserStatus.isValid(statusClaim)) {
            throw new BadCredentialsException("invalid claim");
        }
        User user = userMapper.selectById(id);
        if (user == null || !UserStatus.ENABLED.matches(user.status)
                || !user.status.equals(statusClaim) || !username.equals(user.username) || role != user.role) {
            throw new BadCredentialsException("inactive user");
        }
        return new BookingPrincipal(user.id, user.username, user.role);
    }
}
