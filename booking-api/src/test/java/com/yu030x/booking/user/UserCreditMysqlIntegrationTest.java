package com.yu030x.booking.user;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requires USER_CREDIT_MYSQL_URL (or the DB_* equivalents) pointing at a
 * private MySQL 8 schema; missing variables surface as an explicit failure,
 * never a skip.
 */
@SpringBootTest(classes = BookingApplication.class, properties = {
        "booking.identity.enabled=true",
        "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class UserCreditMysqlIntegrationTest {

    @Autowired
    private UserCreditPort userCreditPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<String> cleanupUsernames = new ArrayList<>();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> env("USER_CREDIT_MYSQL_URL", "DB_URL"));
        registry.add("DB_USERNAME", () -> env("USER_CREDIT_MYSQL_USERNAME", "DB_USERNAME"));
        registry.add("DB_PASSWORD", () -> env("USER_CREDIT_MYSQL_PASSWORD", "DB_PASSWORD"));
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

    @BeforeEach
    void requireRealMysql8() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        int major = Integer.parseInt(version.replaceFirst("^(\\d+).*", "$1"));
        assertThat(major).as("MySQL major version").isGreaterThanOrEqualTo(8);
    }

    @AfterEach
    void removeRowsCreatedByTest() {
        cleanupUsernames.forEach(username ->
                jdbc.update("DELETE FROM `user` WHERE `username` = ?", username));
    }

    @Test
    void deductionReturnsUpdatedCreditAndPersistsAtomically() {
        long userId = insertUser(100);

        int resultingCredit = userCreditPort.applyDeduction(userId, -10);

        assertThat(resultingCredit).isEqualTo(90);
        assertThat(creditScore(userId)).isEqualTo(90);
    }

    @Test
    void deductionClampsAtZeroInsteadOfGoingNegative() {
        long userId = insertUser(3);

        int resultingCredit = userCreditPort.applyDeduction(userId, -10);

        assertThat(resultingCredit).isZero();
        assertThat(creditScore(userId)).isZero();
    }

    @Test
    void missingOrLogicallyDeletedUserFailsExplicitlyWithoutChanges() {
        long userId = insertUser(100);
        jdbc.update("UPDATE `user` SET deleted = 1 WHERE id = ?", userId);

        assertThatThrownBy(() -> userCreditPort.applyDeduction(userId, -5))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.errorCode).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception).hasMessage("user not found");
                });
        assertThat(creditScore(userId)).isEqualTo(100);

        assertThatThrownBy(() -> userCreditPort.applyDeduction(999999999L, -5))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.errorCode).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void deductionJoinsCallerTransactionAndRollbackRevertsIt() {
        long userId = insertUser(100);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status ->
                assertThat(userCreditPort.applyDeduction(userId, -30)).isEqualTo(70));
        assertThat(creditScore(userId)).isEqualTo(70);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            assertThat(userCreditPort.applyDeduction(userId, -20)).isEqualTo(50);
            throw new IllegalStateException("caller rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(creditScore(userId)).isEqualTo(70);
    }

    @Test
    void concurrentDeductionsDoNotLoseUpdates() throws Exception {
        long userId = insertUser(100);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, SECONDS)) {
                        throw new IllegalStateException("concurrent deduction start timed out");
                    }
                    return userCreditPort.applyDeduction(userId, -5);
                }));
            }

            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(20, SECONDS)).isBetween(0, 95);
            }
            assertThat(creditScore(userId)).isEqualTo(60);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    private long insertUser(int creditScore) {
        String username = "t10_credit_" + UUID.randomUUID().toString().replace("-", "");
        cleanupUsernames.add(username);
        jdbc.update("INSERT INTO `user` (`username`, `password`, `real_name`, `credit_score`) "
                + "VALUES (?, ?, ?, ?)", username, "not-a-login-hash", "Credit Port User", creditScore);
        return jdbc.queryForObject(
                "SELECT id FROM `user` WHERE username = ? AND deleted = 0", Long.class, username);
    }

    private int creditScore(long userId) {
        return jdbc.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
    }
}
