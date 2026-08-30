package com.yu030x.booking.violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.task.NoShowItemProcessor;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in MySQL 8 evidence for tasks 5.3/5.4: atomic no-show commit, item
 * rollback, repeated-scan idempotency, and the concurrent race. Run with
 * BOOKING_MYSQL8_TEST=true plus DB_URL, DB_USERNAME, DB_PASSWORD; the test
 * fails explicitly when the enabled target is not MySQL 8 or newer and never
 * silently skips while claiming success.
 */
@SpringBootTest(classes = BookingApplication.class,
        properties = {"booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@EnabledIfEnvironmentVariable(named = "BOOKING_MYSQL8_TEST", matches = "(?i:true)")
class NoShowMysqlIntegrationTest {
    @Autowired
    private NoShowItemProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<Long> bookingIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void requireRealMysql8() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualToIgnoringCase("MySQL");
            assertThat(metadata.getDatabaseMajorVersion()).isGreaterThanOrEqualTo(8);
        }
    }

    @AfterEach
    void removeRowsCreatedByTest() {
        for (Long bookingId : bookingIds) {
            jdbcTemplate.update("DELETE FROM booking_slot WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM violation_record WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM booking WHERE id = ?", bookingId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    @Test
    void noShowCommitsStatusViolationCreditFloorAndSlotReleaseAtomically() {
        long userId = seedUser(5);
        long bookingId = seedConfirmedBooking(userId, LocalDateTime.now().minusHours(1));
        seedSlots(bookingId, 2);

        boolean processed = processor.process(bookingId, userId);

        assertThat(processed).isTrue();
        assertThat(bookingStatus(bookingId)).isEqualTo("NO_SHOW");
        assertThat(scalar("SELECT COUNT(*) FROM violation_record WHERE booking_id = " + bookingId
                + " AND violation_type = 'NO_SHOW' AND score_change = -10")).isEqualTo(1L);
        int credit = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(credit).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM booking_slot WHERE booking_id = " + bookingId)).isZero();
    }

    @Test
    void failingCreditUpdateRollsBackTheWholeItemWithoutPartialState() {
        long userId = seedUser(100);
        long bookingId = seedConfirmedBooking(userId, LocalDateTime.now().minusHours(1));
        seedSlots(bookingId, 2);
        jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);

        assertThatThrownBy(() -> processor.process(bookingId, userId))
                .isInstanceOf(BizException.class);

        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(scalar("SELECT COUNT(*) FROM booking_slot WHERE booking_id = " + bookingId)).isEqualTo(2L);
        assertThat(scalar("SELECT COUNT(*) FROM violation_record WHERE booking_id = " + bookingId)).isZero();
    }

    @Test
    void repeatedProcessingIsIdempotentAndNeverDoubleDeducts() {
        long userId = seedUser(100);
        long bookingId = seedConfirmedBooking(userId, LocalDateTime.now().minusHours(1));
        seedSlots(bookingId, 1);

        assertThat(processor.process(bookingId, userId)).isTrue();
        assertThat(processor.process(bookingId, userId)).isFalse();

        assertThat(scalar("SELECT COUNT(*) FROM violation_record WHERE booking_id = " + bookingId)).isEqualTo(1L);
        int credit = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(credit).isEqualTo(90);
    }

    @Test
    void concurrentProcessingAllowsExactlyOneWinnerAndOneDeduction() throws Exception {
        long userId = seedUser(100);
        long bookingId = seedConfirmedBooking(userId, LocalDateTime.now().minusHours(1));
        seedSlots(bookingId, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return processor.process(bookingId, userId);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(scalar("SELECT COUNT(*) FROM violation_record WHERE booking_id = "
                    + bookingId)).isEqualTo(1L);
            int credit = jdbcTemplate.queryForObject(
                    "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
            assertThat(credit).isEqualTo(90);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long scalar(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private long seedUser(int creditScore) {
        String username = "t10_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO `user` (`username`, `password`, `real_name`, `credit_score`) "
                + "VALUES (?, ?, ?, ?)", username, "not-a-login-hash", "T10 User", creditScore);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
        userIds.add(userId);
        return userId;
    }

    private long seedConfirmedBooking(long userId, LocalDateTime startTime) {
        String bookingNo = "BK" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbcTemplate.update("""
                INSERT INTO booking (booking_no, user_id, resource_id, start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED')
                """, bookingNo, userId, 7L, startTime, startTime.plusHours(1));
        long bookingId = jdbcTemplate.queryForObject(
                "SELECT id FROM booking WHERE booking_no = ?", Long.class, bookingNo);
        bookingIds.add(bookingId);
        return bookingId;
    }

    private void seedSlots(long bookingId, int count) {
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
        for (int index = 0; index < count; index++) {
            jdbcTemplate.update(
                    "INSERT INTO booking_slot (resource_id, slot_time, booking_id) VALUES (?, ?, ?)",
                    9000L + bookingId, base.plusMinutes(30L * index), bookingId);
        }
    }
}
