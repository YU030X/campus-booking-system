package com.yu030x.booking.violation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.violation.port.ViolationPort;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Opt-in MySQL 8 consumer contract evidence for task 5.5: the REQUIRED
 * ViolationPort participates in the caller transaction (rollback discards both
 * the violation row and the credit deduction), keeps uk_booking_type
 * uniqueness, and applies LATE_CANCEL = -5 floored at zero. Run with
 * BOOKING_MYSQL8_TEST=true plus DB_URL, DB_USERNAME, DB_PASSWORD.
 */
@SpringBootTest(classes = BookingApplication.class,
        properties = {"booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@EnabledIfEnvironmentVariable(named = "BOOKING_MYSQL8_TEST", matches = "(?i:true)")
class ViolationPortLateCancelMysqlIntegrationTest {
    @Autowired
    private ViolationPort violationPort;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
            jdbcTemplate.update("DELETE FROM violation_record WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    @Test
    void lateCancelCommitsUniqueMinusFiveRecordAndCreditDeductionInCallerTransaction() {
        long userId = seedUser(100);

        transactionTemplate.executeWithoutResult(status ->
                violationPort.recordLateCancel(424242L, userId));

        long violations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM violation_record
                WHERE booking_id = 424242 AND violation_type = 'LATE_CANCEL' AND score_change = -5
                """, Long.class);
        assertThat(violations).isEqualTo(1L);
        int credit = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(credit).isEqualTo(95);
    }

    @Test
    void callerRollbackDiscardsBothViolationRowAndCreditDeduction() {
        long userId = seedUser(100);

        transactionTemplate.executeWithoutResult(status -> {
            violationPort.recordLateCancel(424243L, userId);
            status.setRollbackOnly();
        });

        long violations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE booking_id = 424243", Long.class);
        assertThat(violations).isZero();
        int credit = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(credit).isEqualTo(100);
    }

    @Test
    void repeatedLateCancelNeverDoubleDeductsAndFloorsAtZero() {
        long userId = seedUser(3);

        transactionTemplate.executeWithoutResult(status ->
                violationPort.recordLateCancel(424244L, userId));
        transactionTemplate.executeWithoutResult(status ->
                violationPort.recordLateCancel(424244L, userId));

        long violations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE booking_id = 424244 "
                        + "AND violation_type = 'LATE_CANCEL'", Long.class);
        assertThat(violations).isEqualTo(1L);
        int credit = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(credit).isZero();
    }

    private long seedUser(int creditScore) {
        String username = "t10p_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO `user` (`username`, `password`, `real_name`, `credit_score`) "
                + "VALUES (?, ?, ?, ?)", username, "not-a-login-hash", "T10 Port User", creditScore);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
        userIds.add(userId);
        return userId;
    }
}
