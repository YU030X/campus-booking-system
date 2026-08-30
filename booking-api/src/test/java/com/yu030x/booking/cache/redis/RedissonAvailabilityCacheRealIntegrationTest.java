package com.yu030x.booking.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.cache.invalidate.AfterCommitInvalidationCoordinator;
import com.yu030x.booking.cache.invalidate.AvailabilityInvalidationRequest;
import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import com.yu030x.booking.cache.port.AvailabilityReadResult;
import com.yu030x.booking.cache.ttl.AvailabilityCacheTtl;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * Required real-Redis acceptance for T12 availability caching. Missing
 * {@code REDIS_HOST} is an explicit failure rather than a skipped test.
 */
@Tag("real-redis")
class RedissonAvailabilityCacheRealIntegrationTest {

    @Test
    void verifiesRealHitMissTtlInvalidationRollbackAndOutageContainment() {
        String host = requiredEnvironment("REDIS_HOST");
        String password = environment("REDIS_PASSWORD");
        int port = integerEnvironment("REDIS_PORT", 6379);

        Map<String, Object> properties = Map.ofEntries(
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("server.port", "0"),
                Map.entry("spring.autoconfigure.exclude",
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration"),
                Map.entry("booking.identity.enabled", "false"),
                Map.entry("springdoc.api-docs.enabled", "false"),
                Map.entry("springdoc.swagger-ui.enabled", "false"),
                Map.entry("booking.redis.enabled", "true"),
                Map.entry("booking.redis.host", host),
                Map.entry("booking.redis.port", Integer.toString(port)),
                Map.entry("booking.redis.password", password),
                Map.entry("booking.redis.database", "0"),
                Map.entry("booking.redis.connect-timeout-ms", "3000"),
                Map.entry("booking.redis.command-timeout-ms", "5000"),
                Map.entry("booking.cache.enabled", "true"));

        StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("real-cache", properties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BookingApplication.class)
                .web(WebApplicationType.SERVLET)
                .environment(environment)
                .run();

        AvailabilityCachePort cache = context.getBean(AvailabilityCachePort.class);
        AfterCommitInvalidationCoordinator coordinator =
                context.getBean(AfterCommitInvalidationCoordinator.class);
        RedissonClient redisson = context.getBean(RedissonClient.class);
        RedissonAvailabilityCache shutdownProbe =
                new RedissonAvailabilityCache(new StaticProvider(redisson));
        long resourceId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
        String key = AvailabilityCacheKey.of(resourceId, LocalDate.of(2026, 8, 30));
        String payload = "{\"slots\":[\"14:00\",\"14:30\"]}";

        try {
            assertThat(cache.invalidate(key)).isTrue();
            assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.MISS);

            assertThat(cache.write(key, payload)).isTrue();
            AvailabilityReadResult hit = cache.read(key);
            assertThat(hit.status()).isEqualTo(AvailabilityReadResult.Status.HIT);
            assertThat(hit.value()).isEqualTo(payload);

            int expectedSeconds = AvailabilityCacheTtl.ttlSeconds(key);
            long remainingMillis = redisson.<String>getBucket(key).remainTimeToLive();
            assertThat(expectedSeconds).isBetween(300, 900);
            assertThat(remainingMillis)
                    .isPositive()
                    .isLessThanOrEqualTo(expectedSeconds * 1000L)
                    .isGreaterThanOrEqualTo((expectedSeconds - 10L) * 1000L);

            TransactionSynchronizationManager.initSynchronization();
            try {
                coordinator.scheduleAfterCommit(
                        new AvailabilityInvalidationRequest(key, "real-cache-commit"));
                assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.HIT);
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.MISS);

            assertThat(cache.write(key, payload)).isTrue();
            TransactionSynchronizationManager.initSynchronization();
            try {
                coordinator.scheduleAfterCommit(
                        new AvailabilityInvalidationRequest(key, "real-cache-rollback"));
                TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.HIT);
        } finally {
            redisson.getKeys().delete(key);
            context.close();
        }

        assertThat(redisson.isShutdown()).isTrue();
        assertThat(shutdownProbe.read(key).status())
                .isEqualTo(AvailabilityReadResult.Status.FAILURE);
        assertThat(shutdownProbe.write(key, payload)).isFalse();
        assertThat(shutdownProbe.invalidate(key)).isFalse();
    }

    private static String requiredEnvironment(String name) {
        String value = environment(name);
        if (value.isBlank()) {
            throw new IllegalStateException(name + " must be set to a private Redis test endpoint");
        }
        return value;
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static int integerEnvironment(String name, int defaultValue) {
        String value = environment(name);
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be an integer", exception);
        }
    }

    private record StaticProvider(RedissonClient client) implements ObjectProvider<RedissonClient> {
        @Override
        public RedissonClient getObject(Object... args) {
            return client;
        }

        @Override
        public RedissonClient getIfAvailable() {
            return client;
        }

        @Override
        public RedissonClient getIfUnique() {
            return client;
        }

        @Override
        public RedissonClient getObject() {
            return client;
        }
    }
}
