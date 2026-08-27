package com.yu030x.booking.cache.config;

import com.yu030x.booking.cache.invalidate.AfterCommitInvalidationCoordinator;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import com.yu030x.booking.cache.redis.RedissonAvailabilityCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Independent switch for the availability Cache Aside slice
 * ({@code booking.cache.enabled}, default false). No shared configuration,
 * pom, or owner-package files are modified by this slice. When disabled none
 * of these beans exist, so Redis is never contacted.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.cache.enabled", havingValue = "true", matchIfMissing = false)
public class AvailabilityCacheConfiguration {

    @Bean
    public AvailabilityCachePort availabilityCachePort(ObjectProvider<RedissonClient> clients) {
        return new RedissonAvailabilityCache(clients);
    }

    @Bean
    public AfterCommitInvalidationCoordinator afterCommitInvalidationCoordinator(
            AvailabilityCachePort cache) {
        return new AfterCommitInvalidationCoordinator(cache);
    }
}
