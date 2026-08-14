package com.yu030x.booking.resource;

import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@TestConfiguration
@EnableMethodSecurity
@Import(ResourceTestSecurityConfig.MethodSecurityAccessDeniedHandler.class)
public class ResourceTestSecurityConfig {
    @Bean
    @Order(2)
    SecurityFilterChain resourceTestSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterBefore(testPrincipalFilter(), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, 401, 40100, "unauthenticated"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, 403, 40300, "forbidden")))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    private OncePerRequestFilter testPrincipalFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                String role = request.getHeader("X-Test-Role");
                if (("ADMIN".equals(role) || "STUDENT".equals(role))
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    var authentication = UsernamePasswordAuthenticationToken.authenticated(
                            "resource-test-principal",
                            "N/A",
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private static void writeError(
            HttpServletResponse response,
            int status,
            int code,
            String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message
                + "\",\"data\":null}");
    }

    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class MethodSecurityAccessDeniedHandler {
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Result<Void>> accessDenied(AccessDeniedException exception) {
            ErrorCode code = ErrorCode.FORBIDDEN;
            return ResponseEntity.status(code.httpStatus)
                    .body(new Result<>(code.code, "forbidden", null));
        }
    }
}
