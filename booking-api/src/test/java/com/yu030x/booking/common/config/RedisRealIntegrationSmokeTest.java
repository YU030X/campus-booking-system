package com.yu030x.booking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Required opt-in smoke test. It deliberately fails when a private test Redis endpoint
 * is not supplied; it must never turn an unavailable external prerequisite into a skip.
 * The environment contract is REDIS_HOST plus optional REDIS_PORT (default 6379),
 * REDIS_PASSWORD (blank means absent), REDIS_CONNECT_TIMEOUT_MS (integer 100..10000,
 * default 3000), and REDIS_COMMAND_TIMEOUT_MS (integer 100..30000, default 5000).
 */
class RedisRealIntegrationSmokeTest {
    @Test
    void exercisesRealRedisClientsAndOrderlyShutdown() throws Exception {
        String host = requiredEnvironment("REDIS_HOST");
        String password = environment("REDIS_PASSWORD");
        int port = integerEnvironment("REDIS_PORT", 6379);
        int connectTimeoutMs = integerEnvironment("REDIS_CONNECT_TIMEOUT_MS", 3000);
        int commandTimeoutMs = integerEnvironment("REDIS_COMMAND_TIMEOUT_MS", 5000);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("redis-smoke", Map.of(
                "booking.redis.enabled", "true",
                "booking.redis.host", host,
                "booking.redis.port", Integer.toString(port),
                "booking.redis.password", password,
                "booking.redis.database", "0",
                "booking.redis.connect-timeout-ms", Integer.toString(connectTimeoutMs),
                "booking.redis.command-timeout-ms", Integer.toString(commandTimeoutMs))));
        context.register(RedisFoundationConfiguration.class);

        String key = "redis-foundation-smoke:" + UUID.randomUUID();
        try {
            context.refresh();
            RedisTemplate<String, String> template = context.getBean(RedisTemplate.class);
            RedissonClient redisson = context.getBean(RedissonClient.class);

            String json = "{\"kind\":\"smoke\",\"value\":1}";
            template.opsForValue().set(key, json, Duration.ofMinutes(1));
            assertThat(template.opsForValue().get(key)).isEqualTo(json);

            RLock lock = redisson.getLock(key + ":lock");
            assertThat(lock.tryLock(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(lock.isHeldByCurrentThread()).isTrue();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } finally {
            if (context.isActive()) {
                RedisTemplate<String, String> template = context.getBean(RedisTemplate.class);
                template.delete(key);
            }
            context.close();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = environment(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to a private Redis smoke-test endpoint");
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
}
