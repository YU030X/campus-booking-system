package com.yu030x.booking.statistics.config;

import com.yu030x.booking.statistics.mapper.StatisticsMapper;
import com.yu030x.booking.statistics.service.StatisticsService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Independent switch for the administrator statistics slice
 * ({@code booking.statistics.enabled}, default false). No shared configuration,
 * pom, sql, or owner packages are modified. Authentication (401) and ADMIN
 * scoping (403) come from the existing global /api/v1/admin/** security chain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.statistics.enabled", havingValue = "true", matchIfMissing = false)
@MapperScan(basePackages = "com.yu030x.booking.statistics.mapper", lazyInitialization = "true")
public class StatisticsConfiguration {

    @Bean
    public StatisticsService statisticsService(StatisticsMapper mapper) {
        return new StatisticsService(mapper);
    }
}
