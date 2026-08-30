package com.yu030x.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.notification.event.NotificationRequestedEvent;
import com.yu030x.booking.notification.service.NotificationDelivery;
import com.yu030x.booking.notification.service.NotificationService;
import com.yu030x.booking.notification.vo.NotificationView;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in REAL MySQL 8 evidence for tasks 3.2-3.5: createdAt/id descending
 * paging, owner-only idempotent read marking with unified foreign/missing 404,
 * field-boundary aborts, AFTER_COMMIT versus rollback event semantics,
 * NULL-bizId identity dedup, and the two-consumer barrier race asserting a
 * single inserted row.
 *
 * Run with BOOKING_MYSQL8_TEST=true plus DB_URL, DB_USERNAME and DB_PASSWORD
 * against MySQL 8; the guard fails loudly when the target is not MySQL >= 8.
 */
@SpringBootTest(classes = BookingApplication.class,
        properties = {"booking.notifications.enabled=true",
                "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@EnabledIfEnvironmentVariable(named = "BOOKING_MYSQL8_TEST", matches = "(?i:true)")
class NotificationMysqlIntegrationTest {
    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDelivery delivery;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

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
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    @Test
    void pagingReturnsCreatedAtDescThenIdDescForTheCurrentUserIdentity() {
        long userId = seedUser();
        long olderId = seedNotification(userId, "older", "2026-08-26 09:00:00");
        long sameTimeFirst = seedNotification(userId, "same-1", "2026-08-27 10:00:00");
        long sameTimeSecond = seedNotification(userId, "same-2", "2026-08-27 10:00:00");

        List<NotificationView> pageOne =
                notificationService.pageForCurrentUser(userId, 1, 2).records();

        assertThat(pageOne).extracting(NotificationView::id)
                .containsExactly(sameTimeSecond, sameTimeFirst);
        List<NotificationView> pageTwo =
                notificationService.pageForCurrentUser(userId, 2, 2).records();
        assertThat(pageTwo).extracting(NotificationView::id).containsExactly(olderId);
    }

    @Test
    void ownerReadIsIdempotentWhileForeignAndMissingCollapseInto404WithoutMutation() {
        long owner = seedUser();
        long stranger = seedUser();
        long titleId = seedNotification(owner, "owner-read", "2026-08-27 08:00:00");

        notificationService.markReadForCurrentUser(owner, titleId);
        notificationService.markReadForCurrentUser(owner, titleId);

        assertThat(readFlag(titleId)).isEqualTo(1);
        assertThatThrownBy(() -> notificationService.markReadForCurrentUser(stranger, titleId))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode.httpStatus).isEqualTo(404));
        assertThatThrownBy(() -> notificationService.markReadForCurrentUser(stranger, 999_999_999L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode.httpStatus).isEqualTo(404));
        assertThat(scalar("SELECT COUNT(*) FROM notification WHERE is_read = 1"))
                .isEqualTo(1L);
    }

    @Test
    void outOfBoundsFieldAbortsBeforeAnyInsertLeavingZeroRows() {
        seedUser();

        assertThatThrownBy(() -> delivery.deliver(new NotificationRequestedEvent(
                userIds.get(0), "t".repeat(101), "c", "TYPE", 1L)))
                .isInstanceOf(BizException.class);

        assertThat(scalar("SELECT COUNT(*) FROM notification")).isZero();
    }

    @Test
    void committedTransactionEmitsExactlyOnceAndRolledBackTransactionEmitsNothing()
            throws InterruptedException {
        long userId = seedUser();

        transactionTemplate.executeWithoutResult(status ->
                publisher.publishEvent(new NotificationRequestedEvent(
                        userId, "commit side", "内容", "BOOKING_APPROVED", 900L)));
        waitForCount("SELECT COUNT(*) FROM notification WHERE biz_id = 900 AND type = "
                + "'BOOKING_APPROVED'", 1);

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            publisher.publishEvent(new NotificationRequestedEvent(
                    userId, "rollback side", "内容", "REMIND", 901L));
        });
        Thread.sleep(200);

        assertThat(scalar("SELECT COUNT(*) FROM notification WHERE biz_id = 901")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM notification WHERE user_id = " + userId))
                .isEqualTo(1L);
    }

    @Test
    void nullBizIdDuplicatesAreOnlyEqualAmongNulls() throws InterruptedException {
        long userId = seedUser();

        delivery.deliver(new NotificationRequestedEvent(userId, "one", "内容", "REMIND", null));
        delivery.deliver(new NotificationRequestedEvent(userId, "two", "内容", "REMIND", null));

        assertThat(scalar("SELECT COUNT(*) FROM notification WHERE type = 'REMIND' "
                + "AND biz_id IS NULL AND user_id = " + userId)).isEqualTo(1L);
        delivery.deliver(new NotificationRequestedEvent(userId, "three", "内容", "REMIND", 700L));
        assertThat(scalar("SELECT COUNT(*) FROM notification WHERE type = 'REMIND' "
                + "AND user_id = " + userId)).isEqualTo(2L);
    }

    @Test
    void twoConcurrentFirstDeliveriesBehindABarrierInsertExactlyOneRow() throws Exception {
        long userId = seedUser();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    delivery.deliver(new NotificationRequestedEvent(
                            userId, "barrier", "内容", "VIOLATION", 800L));
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            assertThat(scalar("SELECT COUNT(*) FROM notification WHERE user_id = " + userId
                    + " AND type = 'VIOLATION' AND biz_id = 800")).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long scalar(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? -1L : value;
    }

    private int readFlag(long notificationId) {
        Integer flag = jdbcTemplate.queryForObject(
                "SELECT is_read FROM notification WHERE id = ?", Integer.class, notificationId);
        return flag == null ? -1 : flag;
    }

    private void waitForCount(String sql, long expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (scalar(sql) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(scalar(sql)).as("expected row count %d for %s", expected, sql).isEqualTo(expected);
    }

    private long seedUser() {
        String username = "t12_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO `user` (`username`, `password`, `real_name`, `credit_score`) "
                + "VALUES (?, ?, ?, ?)", username, "not-a-login-hash", "T12 User", 100);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
        userIds.add(userId);
        return userId;
    }

    /** Inserted directly so paging evidence stays independent of the consumer path. */
    private long seedNotification(long userId, String title, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO notification (user_id, title, content, type, biz_id, is_read, created_at)
                VALUES (?, ?, ?, 'REMIND', NULL, 0, ?)
                """, userId, title, "T12 paging fixture " + title, createdAt);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notification WHERE user_id = ? AND title = ?", Long.class,
                userId, title);
    }
}
