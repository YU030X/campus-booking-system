package com.yu030x.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yu030x.booking.booking.service.BookingLockCoordinator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * Opt-in test against a real private Redis endpoint (REDIS_HOST required, optional
 * REDIS_PORT/REDIS_PASSWORD). A missing endpoint surfaces as an explicit failure,
 * never a silent skip. No MySQL connection is created in this class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingRedisLockIntegrationTest {
    private static final String KEY_PREFIX = "booking:lock:";
    private static final long RESOURCE = 4201L;
    private static final LocalDate DATE = LocalDate.of(2027, 1, 10);

    private ConfigurableApplicationContext context;
    private RedissonClient redisson;
    private BookingLockCoordinator coordinator;

    @BeforeAll
    void startContextAgainstRealRedis() {
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
                Map.entry("booking.redis.database", "0"));

        StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("booking-redis-it", properties));
        context = new SpringApplicationBuilder(com.yu030x.booking.BookingApplication.class)
                .web(WebApplicationType.SERVLET)
                .environment(environment)
                .run();
        redisson = context.getBean(RedissonClient.class);
        coordinator = context.getBean(BookingLockCoordinator.class);
    }

    @AfterAll
    void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void acquiresReleasesAndOnlyOwnerUnlocks() throws Exception {
        String key = KEY_PREFIX + RESOURCE + ":" + DATE;
        AtomicBoolean heldInside = new AtomicBoolean(false);
        AtomicBoolean heldOutside = new AtomicBoolean(true);

        String outcome = coordinator.withResourceDateLock(RESOURCE, DATE, () -> {
            heldInside.set(redisson.getLock(key).isHeldByCurrentThread()
                    && redisson.getLock(key).isLocked());
            return "ok";
        });

        assertThat(outcome).isEqualTo("ok");
        assertThat(heldInside).isTrue();
        assertThat(redisson.getLock(key).isLocked()).isFalse();

        RLock lock = redisson.getLock(key);
        assertThat(lock.tryLock(3, TimeUnit.SECONDS)).isTrue();
        CountDownLatch released = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                assertThat(lock.isHeldByCurrentThread()).isFalse();
                heldOutside.set(lock.isHeldByCurrentThread() || !lock.isLocked());
            } finally {
                lock.unlock();
                released.countDown();
            }
        });
        owner.start();
        assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(heldOutside).isFalse();
    }

    @Test
    void contendedSameKeyWaitsThreeSecondsThenFailsClosedWithSystemBusy() throws Exception {
        RLock holder = redisson.getLock(KEY_PREFIX + 4302L + ":2027-01-11");
        assertThat(holder.tryLock(3, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            BizException exception = assertThrows(BizException.class, () ->
                    coordinator.withResourceDateLock(4302L, LocalDate.of(2027, 1, 11), () -> "never"));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(exception.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
            assertThat(exception.getMessage()).isEqualTo(BookingMessages.SYSTEM_BUSY);
            assertThat(waitedMs).as("waited at least ~3s").isGreaterThanOrEqualTo(2900L);
        } finally {
            holder.unlock();
        }
    }

    @Test
    void differentResourceOrDateKeysProgressIndependentlyWithoutGlobalSerialization() throws Exception {
        RLock holder = redisson.getLock(KEY_PREFIX + RESOURCE + ":" + DATE);
        assertThat(holder.tryLock(3, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            String other = coordinator.withResourceDateLock(RESOURCE + 1, DATE.plusDays(2), () -> "free");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(other).isEqualTo("free");
            assertThat(elapsedMs).as("independent domain must not wait for the 3s contention window")
                    .isLessThan(2500L);
        } finally {
            holder.unlock();
        }
    }

    @Test
    void unreachableRedisFailsClosedWithSystemBusyAndNeverPretendsToHoldTheLock() {
        Config config = new Config();
        config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6390")
                .setConnectTimeout(300)
                .setTimeout(500)
                .setRetryAttempts(0)
                .setRetryInterval(100);
        RedissonClient broken = Redisson.create(config);
        BookingLockCoordinator isolated =
                new BookingLockCoordinator(new StaticProvider(broken));
        try {
            BizException exception = assertThrows(BizException.class, () ->
                    isolated.withResourceDateLock(RESOURCE, DATE, () -> "unlocked-success"));
            assertThat(exception.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
            assertThat(exception.getMessage()).isEqualTo(BookingMessages.SYSTEM_BUSY);
        } finally {
            broken.shutdown(0, 2, TimeUnit.SECONDS);
        }
    }

    private record StaticProvider(RedissonClient client)
            implements org.springframework.beans.factory.ObjectProvider<RedissonClient> {
        @Override
        public RedissonClient getObject(Object... args) { return client; }

        @Override
        public RedissonClient getIfAvailable() { return client; }

        @Override
        public RedissonClient getIfUnique() { return client; }

        @Override
        public RedissonClient getObject() { return client; }
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
        return Integer.parseInt(value);
    }
}
