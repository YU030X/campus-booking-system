package com.yu030x.booking.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yu030x.booking.availability.AvailabilityService;
import com.yu030x.booking.availability.AvailabilityVO;
import com.yu030x.booking.booking.service.BookingLockCoordinator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import com.yu030x.booking.cache.port.AvailabilityReadResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real MySQL 8 + Redis 7 proof for the T06/T07 owner handoffs. */
@Tag("real-redis")
@SpringBootTest(properties = {
        "booking.cache.enabled=true",
        "booking.identity.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AvailabilityCacheAsideRealIntegrationTest {
    private static final String PREFIX = "t12-cache-aside-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AvailabilityService availability;
    @Autowired
    private AvailabilityCachePort cache;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private BookingLockCoordinator bookingLock;

    private long categoryId;
    private long resourceId;
    private long bookingId;
    private LocalDate date;
    private String key;
    private String fixtureName;

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> requiredEnvironment("RESOURCE_MYSQL_URL", "DB_URL"));
        registry.add("DB_USERNAME", () -> requiredEnvironment("RESOURCE_MYSQL_USERNAME", "DB_USERNAME"));
        registry.add("DB_PASSWORD", () -> requiredEnvironment("RESOURCE_MYSQL_PASSWORD", "DB_PASSWORD"));
        registry.add("booking.redis.enabled", () -> "true");
        registry.add("booking.redis.host", () -> requiredEnvironment("REDIS_HOST"));
        registry.add("booking.redis.port", () -> environment("REDIS_PORT", "6379"));
        registry.add("booking.redis.password", () -> environment("REDIS_PASSWORD", ""));
    }

    @BeforeEach
    void createFixture() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertThat(version).startsWith("8.");
        date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(10);
        while (closureExists(0, date)) {
            date = date.plusDays(1);
        }
        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1",
                Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,min_duration_minutes,"
                        + "max_duration_minutes,status,deleted) VALUES (?,?,0,30,30,120,1,0)",
                categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                        + "VALUES (?,?,?,?,0)",
                resourceId, date.getDayOfWeek().getValue(), LocalTime.of(8, 0), LocalTime.of(10, 0));
        bookingId = 980000000L + resourceId;
        key = AvailabilityCacheKey.of(resourceId, date);
        assertThat(cache.invalidate(key)).isTrue();
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM booking_slot WHERE resource_id=? AND booking_id=?", resourceId, bookingId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM resource_category WHERE id=?", categoryId);
    }

    @Test
    void cacheHitInvalidationAndRedisOutageFallbackPreserveBookingFailClosed() {
        AvailabilityVO missResult = availability.get(resourceId, date);
        assertThat(missResult.slots()).hasSize(4).allMatch(AvailabilityVO.SlotVO::available);
        assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.HIT);

        insertOccupied(LocalTime.of(8, 0));
        AvailabilityVO cachedResult = availability.get(resourceId, date);
        assertThat(slot(cachedResult, "08:00").available()).isTrue();

        assertThat(cache.invalidate(key)).isTrue();
        AvailabilityVO refreshed = availability.get(resourceId, date);
        assertThat(slot(refreshed, "08:00").available()).isFalse();

        assertThat(cache.invalidate(key)).isTrue();
        insertOccupied(LocalTime.of(8, 30));
        redisson.shutdown();

        AvailabilityVO outageFallback = availability.get(resourceId, date);
        assertThat(slot(outageFallback, "08:00").available()).isFalse();
        assertThat(slot(outageFallback, "08:30").available()).isFalse();

        AtomicBoolean bookingActionRan = new AtomicBoolean(false);
        BizException busy = assertThrows(BizException.class, () ->
                bookingLock.withResourceDateLock(resourceId, date, () -> {
                    bookingActionRan.set(true);
                    return "unexpected";
                }));
        assertThat(busy.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
        assertThat(busy.getMessage()).isEqualTo(BookingMessages.SYSTEM_BUSY);
        assertThat(bookingActionRan).isFalse();
    }

    private void insertOccupied(LocalTime start) {
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?)",
                resourceId, LocalDateTime.of(date, start), bookingId);
    }

    private AvailabilityVO.SlotVO slot(AvailabilityVO value, String start) {
        return value.slots().stream()
                .filter(candidate -> candidate.startTime().equals(start))
                .findFirst()
                .orElseThrow();
    }

    private boolean closureExists(long scope, LocalDate target) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_closure WHERE resource_id=? AND closure_date=?",
                Integer.class, scope, target) > 0;
    }

    private static String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException(String.join(" or ", names)
                + " must be set to a private integration-test endpoint");
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
