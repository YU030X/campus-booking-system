package com.yu030x.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.BookingCreator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.booking.service.BookingService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.exception.BizException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Requires both a private MySQL 8 schema (RESOURCE_MYSQL_URL or DB_*) and a real
 * private Redis endpoint (REDIS_HOST). Missing prerequisites surface as explicit
 * failures, never silent skips. The historical check-then-insert reproduction in
 * {@code bypassesLockHarness} is isolated to this non-deployed test fixture only.
 */
@SpringBootTest(properties = {
        "booking.identity.enabled=true",
        "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class BookingConcurrencyIntegrationTest {
    private static final String PREFIX = "codex-booking-conc-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingCreator creator;
    @Autowired
    private BookingMapper bookingMapper;

    private long categoryId;
    private long resourceIdA;
    private long resourceIdB;
    private long userId;
    private String fixtureName;
    private LocalDate dateA;
    private LocalDate dateB;

    @DynamicPropertySource
    static void externalProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> env("RESOURCE_MYSQL_URL", "DB_URL"));
        registry.add("DB_USERNAME", () -> env("RESOURCE_MYSQL_USERNAME", "DB_USERNAME"));
        registry.add("DB_PASSWORD", () -> env("RESOURCE_MYSQL_PASSWORD", "DB_PASSWORD"));
        registry.add("booking.redis.enabled", () -> "true");
        registry.add("booking.redis.host", () -> requiredEnv("REDIS_HOST"));
        registry.add("booking.redis.port",
                () -> System.getenv().getOrDefault("REDIS_PORT", "6379"));
        registry.add("booking.redis.password",
                () -> System.getenv().getOrDefault("REDIS_PASSWORD", ""));
        registry.add("booking.redis.database", () -> "0");
    }

    private static String env(String preferred, String fallback) {
        String value = System.getenv(preferred);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallback);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required MySQL environment variable: " + preferred
                    + " or " + fallback);
        }
        return value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to a private Redis test endpoint");
        }
        return value;
    }

    @BeforeEach
    void createFixtures() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        int major = Integer.parseInt(version.replaceFirst("^(\\d+).*", "$1"));
        assertThat(major).as("MySQL major version").isGreaterThanOrEqualTo(8);

        dateA = LocalDate.now().plusDays(10);
        while (!jdbc.queryForList("SELECT closure_date FROM resource_closure WHERE closure_date=?",
                LocalDate.class, dateA).isEmpty()) {
            dateA = dateA.plusDays(1);
        }
        dateB = dateA.plusDays(1);

        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1", Long.class, fixtureName);
        resourceIdA = insertResource(fixtureName + "-A", 0);
        resourceIdB = insertResource(fixtureName + "-B", 0);
        insertRule(resourceIdA, dateA);
        insertRule(resourceIdA, dateB);
        insertRule(resourceIdB, dateA);
        insertRule(resourceIdB, dateB);
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) VALUES (?,?,?,?,1,0)",
                fixtureName + "-stu", "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK", "并发学生", "STUDENT");
        userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, fixtureName + "-stu");
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("DELETE FROM booking_slot WHERE resource_id IN (?,?)", resourceIdA, resourceIdB);
        jdbc.update("DELETE FROM booking WHERE user_id=?", userId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id IN (?,?)", resourceIdA, resourceIdB);
        jdbc.update("DELETE FROM resource WHERE id IN (?,?)", resourceIdA, resourceIdB);
        jdbc.update("DELETE FROM `user` WHERE username LIKE ?", fixtureName + "%");
        jdbc.update("DELETE FROM resource_category WHERE id=? AND name=?", categoryId, fixtureName);
    }

    private long insertResource(String name, int needApproval) {
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,min_duration_minutes,"
                + "max_duration_minutes,status,deleted) VALUES (?,?,?,30,30,240,1,0)", categoryId, name, needApproval);
        return jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, name);
    }

    private void insertRule(long resourceId, LocalDate date) {
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                        + "VALUES (?,?,?,?,0)",
                resourceId, date.getDayOfWeek().getValue(), LocalTime.of(8, 0), LocalTime.of(22, 0));
    }

    private CreateBookingRequest request(long resourceId, LocalDate date, String start, String end) {
        return new CreateBookingRequest(Long.toString(resourceId),
                LocalDateTime.of(date, LocalTime.parse(start)),
                LocalDateTime.of(date, LocalTime.parse(end)), null, 2);
    }

    private record Outcome(BookingView view, BizException failure) {
        boolean succeeded() { return view != null; }
        int code() { return failure == null ? 0 : failure.errorCode.code; }
    }

    private Outcome attempt(long resourceId, LocalDate date, String start, String end) {
        try {
            return new Outcome(bookingService.create(
                    new BookingPrincipal(userId, "conc-stu",
                            com.yu030x.booking.user.UserRole.STUDENT),
                    request(resourceId, date, start, end)), null);
        } catch (BizException exception) {
            return new Outcome(null, exception);
        }
    }

    @Test
    void sixtyConcurrentSameSlotRequestsProduceExactlyOneWinnerAndCompleteSlotSet() throws Exception {
        int contenders = 60;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < contenders; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!startGate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("contender start timed out");
                    }
                    return attempt(resourceIdA, dateA, "14:00", "14:30");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }

            long winners = outcomes.stream().filter(Outcome::succeeded).count();
            assertThat(winners).as("exactly one 201-equivalent winner").isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                    .allSatisfy(outcome -> assertThat(outcome.code()).isEqualTo(43000));

            long winnerBookingId = Long.parseLong(outcomes.stream()
                    .filter(Outcome::succeeded).findFirst().orElseThrow().view().id());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE user_id=? AND status IS NOT NULL",
                    Long.class, userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?",
                    Long.class, winnerBookingId)).isEqualTo(1L);
        } finally {
            startGate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentDatesOnSameResourceAreNotGloballySerialized() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> {
                if (!startGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return attempt(resourceIdA, dateA, "09:00", "09:30");
            });
            Future<Outcome> second = executor.submit(() -> {
                if (!startGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                long begin = System.nanoTime();
                Outcome outcome = attempt(resourceIdA, dateB, "09:00", "09:30");
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                if (elapsedMs > 2500) {
                    throw new IllegalStateException("independent date waited behind a global lock: " + elapsedMs);
                }
                return outcome;
            });

            startGate.countDown();
            Outcome firstOutcome = first.get(30, TimeUnit.SECONDS);
            Outcome secondOutcome = second.get(30, TimeUnit.SECONDS);
            assertThat(firstOutcome.succeeded()).isTrue();
            assertThat(secondOutcome.succeeded()).isTrue();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE resource_id=?",
                    Long.class, resourceIdA)).isEqualTo(2L);
        } finally {
            startGate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentResourcesOnSameDateProceedInParallelWithPerResourceCorrectness() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> {
                if (!startGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return attempt(resourceIdA, dateA, "10:00", "11:00");
            });
            Future<Outcome> second = executor.submit(() -> {
                if (!startGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return attempt(resourceIdB, dateA, "10:00", "11:00");
            });

            startGate.countDown();
            Outcome firstOutcome = first.get(30, TimeUnit.SECONDS);
            Outcome secondOutcome = second.get(30, TimeUnit.SECONDS);
            assertThat(firstOutcome.succeeded()).isTrue();
            assertThat(secondOutcome.succeeded()).isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM booking_slot WHERE resource_id=? AND slot_time=?",
                    Long.class, resourceIdA, LocalDateTime.of(dateA, LocalTime.parse("10:00")))).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM booking_slot WHERE resource_id=? AND slot_time=?",
                    Long.class, resourceIdB, LocalDateTime.of(dateA, LocalTime.parse("10:00")))).isEqualTo(1);
        } finally {
            startGate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void historicalCheckThenInsertRaceIsContainedByTheUniqueKeyWithoutAnyLock() throws Exception {
        int racers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(racers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < racers; index++) {
                futures.add(executor.submit(() -> {
                    if (!startGate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("racer start timed out");
                    }
                    try {
                        return new Outcome(creator.create(userId,
                                request(resourceIdB, dateB, "15:00", "15:30")), null);
                    } catch (BizException exception) {
                        return new Outcome(null, exception);
                    }
                }));
            }
            startGate.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(30, TimeUnit.SECONDS);
                if (outcome.succeeded()) {
                    successes++;
                } else {
                    assertThat(outcome.failure().errorCode.code).isEqualTo(43000);
                    assertThat(outcome.failure().getMessage()).isEqualTo(BookingMessages.SLOT_CONFLICT);
                    conflicts++;
                }
            }
            assertThat(successes).as("unique key admits exactly one racer").isEqualTo(1);
            assertThat(conflicts).isEqualTo(racers - 1);

            Long bookingId = jdbc.queryForObject(
                    "SELECT id FROM booking WHERE user_id=? ORDER BY id DESC LIMIT 1", Long.class, userId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?",
                    Long.class, bookingId)).isEqualTo(1L);
        } finally {
            startGate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
