package com.yu030x.booking.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingCreator;
import com.yu030x.booking.booking.service.DefaultBookingActions;
import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import com.yu030x.booking.cache.port.AvailabilityReadResult;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL 8 + Redis 7 proof for owner mutation hooks and transaction boundaries. */
@Tag("real-redis")
@SpringBootTest(properties = {
        "booking.cache.enabled=true",
        "booking.identity.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MutationCacheInvalidationRealIntegrationTest {
    private static final String PREFIX = "t12-mutation-cache-it-";
    private static final String PAYLOAD = "{\"resourceId\":\"fixture\",\"date\":\"fixture\",\"slots\":[]}";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BookingCreator bookingCreator;
    @Autowired
    private DefaultBookingActions bookingActions;
    @Autowired
    private ResourceCatalogService resources;
    @Autowired
    private AvailabilityCachePort cache;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private TransactionTemplate transactions;

    private String fixtureName;
    private long userId;
    private long categoryId;
    private long resourceId;
    private LocalDate date;
    private String key;

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
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        fixtureName = PREFIX + System.nanoTime();
        date = LocalDate.now(SHANGHAI).plusDays(5);
        while (closureExists(0, date)) {
            date = date.plusDays(1);
        }

        jdbc.update("INSERT INTO user(username,password,real_name,role,credit_score,status,deleted) "
                        + "VALUES (?,?,?,'STUDENT',100,1,0)",
                fixtureName, "test-only", fixtureName);
        userId = jdbc.queryForObject(
                "SELECT id FROM user WHERE username=? AND deleted=0", Long.class, fixtureName);
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)",
                fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1",
                Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,capacity,need_approval,max_advance_days,"
                        + "min_duration_minutes,max_duration_minutes,status,deleted) "
                        + "VALUES (?,?,20,0,30,30,120,1,0)",
                categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                        + "VALUES (?,?,?,?,0)",
                resourceId, date.getDayOfWeek().getValue(), LocalTime.of(8, 0), LocalTime.of(12, 0));
        key = AvailabilityCacheKey.of(resourceId, date);
        redisson.getKeys().deleteByPattern(cachePattern());
    }

    @AfterEach
    void removeFixture() {
        redisson.getKeys().deleteByPattern(cachePattern());
        jdbc.update("DELETE FROM approval_record WHERE booking_id IN "
                + "(SELECT id FROM booking WHERE resource_id=? OR user_id=?)", resourceId, userId);
        jdbc.update("DELETE FROM violation_record WHERE booking_id IN "
                + "(SELECT id FROM booking WHERE resource_id=? OR user_id=?)", resourceId, userId);
        jdbc.update("DELETE FROM booking_slot WHERE resource_id=? OR booking_id IN "
                + "(SELECT id FROM booking WHERE resource_id=? OR user_id=?)", resourceId, resourceId, userId);
        jdbc.update("DELETE FROM booking WHERE resource_id=? OR user_id=?", resourceId, userId);
        jdbc.update("DELETE FROM resource_closure WHERE resource_id=? OR reason=?", resourceId, fixtureName);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM resource_category WHERE id=?", categoryId);
        jdbc.update("DELETE FROM user WHERE id=?", userId);
    }

    @Test
    void bookingCreateInvalidatesOnlyAfterCommitAndPreservesCacheOnRollback() {
        writeHit();
        var committed = bookingCreator.create(userId, request(LocalTime.of(8, 0), "commit"));

        assertMiss();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE id=?", Integer.class,
                Long.parseLong(committed.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?", Integer.class,
                Long.parseLong(committed.id()))).isEqualTo(2);

        writeHit();
        transactions.executeWithoutResult(status -> {
            bookingCreator.create(userId, request(LocalTime.of(10, 0), "rollback"));
            assertHit();
            status.setRollbackOnly();
        });

        assertHit();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE user_id=? AND purpose='rollback'",
                Integer.class, userId)).isZero();
    }

    @Test
    void terminalBookingActionsInvalidateAfterWinningCommit() {
        long rejected = insertBooking("PENDING_APPROVAL", LocalTime.of(8, 0));
        long cancelled = insertBooking("CONFIRMED", LocalTime.of(9, 0));
        long noShow = insertBooking("CONFIRMED", LocalTime.of(10, 0));

        writeHit();
        assertThat(bookingActions.reject(rejected).result())
                .isEqualTo(BookingActionOutcome.Result.WINNER);
        assertTerminalInvalidation(rejected, "REJECTED");

        writeHit();
        assertThat(bookingActions.cancel(cancelled, userId, LocalDateTime.now(SHANGHAI), "test").result())
                .isEqualTo(BookingActionOutcome.Result.WINNER);
        assertTerminalInvalidation(cancelled, "CANCELLED");

        writeHit();
        assertThat(bookingActions.markNoShow(noShow).result())
                .isEqualTo(BookingActionOutcome.Result.WINNER);
        assertTerminalInvalidation(noShow, "NO_SHOW");
    }

    @Test
    void resourceStatusRuleAndClosureMutationsInvalidateAfterCommitButNotRollback() {
        writeHit();
        resources.updateStatus(Long.toString(resourceId), "2");
        assertMiss();
        resources.updateStatus(Long.toString(resourceId), "1");

        writeHit();
        resources.replaceTimeRules(Long.toString(resourceId), List.of(
                new TimeRuleRequest(date.getDayOfWeek().getValue(), "08:00:00", "12:00:00")));
        assertMiss();

        writeHit();
        var closure = resources.createClosure(Long.toString(resourceId),
                new ClosureRequest(date.toString(), fixtureName));
        assertMiss();
        writeHit();
        resources.deleteClosure(Long.toString(resourceId), closure.id());
        assertMiss();

        writeHit();
        var global = resources.createClosure("0", new ClosureRequest(date.toString(), fixtureName));
        assertMiss();
        writeHit();
        resources.deleteClosure("0", global.id());
        assertMiss();

        writeHit();
        transactions.executeWithoutResult(status -> {
            resources.updateStatus(Long.toString(resourceId), "0");
            assertHit();
            status.setRollbackOnly();
        });
        assertHit();
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id=?", Integer.class, resourceId))
                .isEqualTo(1);
    }

    private CreateBookingRequest request(LocalTime start, String purpose) {
        LocalDateTime from = LocalDateTime.of(date, start);
        return new CreateBookingRequest(Long.toString(resourceId), from, from.plusHours(1), purpose, 1);
    }

    private long insertBooking(String status, LocalTime start) {
        String bookingNo = "T12" + Long.toUnsignedString(System.nanoTime());
        LocalDateTime from = LocalDateTime.of(date, start);
        jdbc.update("INSERT INTO booking(booking_no,user_id,resource_id,start_time,end_time,purpose,"
                        + "attendee_count,status,deleted) VALUES (?,?,?,?,?,?,1,?,0)",
                bookingNo, userId, resourceId, from, from.plusHours(1), fixtureName, status);
        long bookingId = jdbc.queryForObject(
                "SELECT id FROM booking WHERE booking_no=?", Long.class, bookingNo);
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?),(?,?,?)",
                resourceId, from, bookingId, resourceId, from.plusMinutes(30), bookingId);
        return bookingId;
    }

    private void assertTerminalInvalidation(long bookingId, String status) {
        assertMiss();
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?",
                Integer.class, bookingId)).isZero();
    }

    private void writeHit() {
        assertThat(cache.write(key, PAYLOAD)).isTrue();
        assertHit();
    }

    private void assertHit() {
        assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.HIT);
    }

    private void assertMiss() {
        assertThat(cache.read(key).status()).isEqualTo(AvailabilityReadResult.Status.MISS);
    }

    private String cachePattern() {
        return "resource:available-slots:" + resourceId + ":*";
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
