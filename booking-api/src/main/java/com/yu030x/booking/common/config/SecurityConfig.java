package com.yu030x.booking.common.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain healthSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.to(HealthEndpoint.class))
                .authorizeHttpRequests(auth -> auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    @Bean
    @Order(Integer.MAX_VALUE)
    SecurityFilterChain denyAllSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(AnyRequestMatcher.INSTANCE)
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    @Bean
    AuthenticationManager foundationAuthenticationManager() {
        return authentication -> { throw new BadCredentialsException("Authentication is not configured"); };
    }
}
