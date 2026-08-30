package com.yu030x.booking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Required opt-in smoke test. It deliberately fails when a private test Redis endpoint
 * is not supplied; it must never turn an unavailable external prerequisite into a skip.
 * The environment contract is REDIS_HOST plus optional REDIS_PORT (default 6379),
 * REDIS_PASSWORD (blank means absent), REDIS_CONNECT_TIMEOUT_MS (integer 100..10000,
 * default 3000), and REDIS_COMMAND_TIMEOUT_MS (integer 100..30000, default 5000).
 */
class RedisRealIntegrationTest {
    @Test
    void startsApplicationExercisesRealRedisAndClosesClients() throws Exception {
        String host = requiredEnvironment("REDIS_HOST");
        String password = environment("REDIS_PASSWORD");
        int port = integerEnvironment("REDIS_PORT", 6379);
        int connectTimeoutMs = integerEnvironment("REDIS_CONNECT_TIMEOUT_MS", 3000);
        int commandTimeoutMs = integerEnvironment("REDIS_COMMAND_TIMEOUT_MS", 5000);

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
                Map.entry("booking.redis.connect-timeout-ms", Integer.toString(connectTimeoutMs)),
                Map.entry("booking.redis.command-timeout-ms", Integer.toString(commandTimeoutMs)));

        StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("redis-smoke", properties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BookingApplication.class)
                .web(WebApplicationType.SERVLET)
                .environment(environment)
                .run();
        RedisTemplate<String, String> template = context.getBean(RedisTemplate.class);
        RedissonClient redisson = context.getBean(RedissonClient.class);
        LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
        String key = "redis-foundation-smoke:" + UUID.randomUUID();
        String redissonKey = key + ":redisson";
        String lockKey = key + ":lock";
        try {
            assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
            assertThat(redisson.getConfig().getCodec()).isInstanceOf(StringCodec.class);
            assertThat(redisson.getConfig().getLockWatchdogTimeout()).isEqualTo(30000L);

            String json = "{\"@class\":\"java.lang.Runtime\",\"value\":1}";
            template.opsForValue().set(key, json, Duration.ofMinutes(1));
            assertThat(template.opsForValue().get(key)).isEqualTo(json);
            redisson.<String>getBucket(redissonKey).set(json, Duration.ofMinutes(1));
            assertThat(redisson.<String>getBucket(redissonKey).get()).isEqualTo(json);

            RLock lock = redisson.getLock(lockKey);
            assertThat(lock.tryLock(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(lock.isHeldByCurrentThread()).isTrue();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } finally {
            template.delete(key);
            redisson.getKeys().delete(redissonKey, lockKey);
            context.close();
        }

        assertThat(redisson.isShutdown()).isTrue();
        assertThat(connectionFactory.isRunning()).isFalse();
    }

    private static String requiredEnvironment(String name) {
        String value = environment(name);
        if (value.isBlank()) {
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
