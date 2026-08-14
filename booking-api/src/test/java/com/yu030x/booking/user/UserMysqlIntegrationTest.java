package com.yu030x.booking.user;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yu030x.booking.auth.AuthService;
import com.yu030x.booking.auth.RegisterRequest;
import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in tests against an externally prepared MySQL 8 schema. Run with
 * BOOKING_MYSQL8_TEST=true plus DB_URL, DB_USERNAME and DB_PASSWORD. The
 * production application configuration supplies the MySQL driver; this test
 * deliberately fails when the enabled target is not MySQL 8 or newer.
 */
@SpringBootTest(classes = BookingApplication.class, properties = {
        "booking.identity.enabled=true",
        "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class UserMysqlIntegrationTest {
    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<String> cleanupUsernames = new ArrayList<>();

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
        cleanupUsernames.forEach(username ->
                jdbcTemplate.update("DELETE FROM `user` WHERE `username` = ?", username));
    }

    @Test
    void concurrentActiveUsernameRegistrationCreatesOneUserAndOne409Conflict() throws Exception {
        String username = uniqueUsername();
        RegisterRequest request = registration(username);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<RegistrationOutcome>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, SECONDS)) {
                        throw new IllegalStateException("concurrent registration start timed out");
                    }
                    try {
                        authService.register(request);
                        return RegistrationOutcome.created();
                    } catch (BizException exception) {
                        return RegistrationOutcome.conflict(exception);
                    }
                }));
            }

            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();
            List<RegistrationOutcome> outcomes = new ArrayList<>();
            for (Future<RegistrationOutcome> future : futures) {
                outcomes.add(future.get(20, SECONDS));
            }

            assertThat(outcomes).extracting(RegistrationOutcome::httpStatus)
                    .containsExactlyInAnyOrder(201, 409);
            RegistrationOutcome conflict = outcomes.stream()
                    .filter(outcome -> outcome.httpStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflict.code()).isEqualTo(41000);
            assertThat(conflict.message()).isEqualTo("username already exists");
            assertThat(activeCount(username)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void logicallyDeletedUsernameCanBeRegisteredAgain() {
        String username = uniqueUsername();
        UserView first = authService.register(registration(username));

        assertThat(userMapper.deleteById(Long.parseLong(first.id()))).isEqualTo(1);
        assertThat(activeCount(username)).isZero();
        assertThat(deletedCount(username)).isEqualTo(1L);

        UserView replacement = authService.register(registration(username));

        assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(activeCount(username)).isEqualTo(1L);
        assertThat(deletedCount(username)).isEqualTo(1L);
    }

    @Test
    void mysqlAppliesRoleCreditStatusAndDeletionDefaults() {
        String username = uniqueUsername();
        jdbcTemplate.update("""
                INSERT INTO `user` (`username`, `password`, `real_name`)
                VALUES (?, ?, ?)
                """, username, "not-a-login-hash", "Default User");

        UserDefaults defaults = jdbcTemplate.queryForObject("""
                SELECT `role`, `credit_score`, `status`, `deleted`
                FROM `user`
                WHERE `username` = ? AND `deleted` = 0
                """, (resultSet, rowNumber) -> new UserDefaults(
                resultSet.getString("role"),
                resultSet.getInt("credit_score"),
                resultSet.getInt("status"),
                resultSet.getInt("deleted")), username);

        assertThat(defaults).isEqualTo(new UserDefaults("STUDENT", 100, 1, 0));
    }

    @Test
    void administratorCanDisableIdempotentlyAndReenableButCannotDisableSelf() {
        String adminUsername = uniqueUsername();
        jdbcTemplate.update("""
                INSERT INTO `user` (`username`, `password`, `real_name`, `role`)
                VALUES (?, ?, ?, 'ADMIN')
                """, adminUsername, "not-a-login-hash", "Test Admin");
        long adminId = jdbcTemplate.queryForObject(
                "SELECT `id` FROM `user` WHERE `username` = ? AND `deleted` = 0",
                Long.class, adminUsername);
        BookingPrincipal admin = new BookingPrincipal(adminId, adminUsername, UserRole.ADMIN);

        String targetUsername = uniqueUsername();
        long targetId = Long.parseLong(authService.register(registration(targetUsername)).id());

        UserView disabled = userService.updateStatus(
                targetId, new UserStatusUpdateRequest(0), admin);
        LocalDateTime disabledAt = updatedAt(targetId);
        UserView unchanged = userService.updateStatus(
                targetId, new UserStatusUpdateRequest(0), admin);

        assertThat(disabled.status()).isZero();
        assertThat(unchanged.status()).isZero();
        assertThat(updatedAt(targetId)).isEqualTo(disabledAt);

        UserView enabled = userService.updateStatus(
                targetId, new UserStatusUpdateRequest(1), admin);
        assertThat(enabled.status()).isEqualTo(1);
        assertThat(status(targetId)).isEqualTo(1);

        assertThatThrownBy(() -> userService.updateStatus(
                adminId, new UserStatusUpdateRequest(0), admin))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_ERROR);
                    assertThat(exception).hasMessage("administrator cannot disable self");
                });
        assertThat(status(adminId)).isEqualTo(1);
    }

    @Test
    void adminPaginationUsesStableCreatedAtThenIdDescendingOrder() {
        String prefix = "t02_order_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 13, 12, 0);
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String username = prefix + "_" + index;
            cleanupUsernames.add(username);
            jdbcTemplate.update("""
                    INSERT INTO `user` (`username`, `password`, `real_name`, `created_at`, `updated_at`)
                    VALUES (?, ?, ?, ?, ?)
                    """, username, "not-a-login-hash", "Ordered User", createdAt, createdAt);
            ids.add(jdbcTemplate.queryForObject(
                    "SELECT `id` FROM `user` WHERE `username` = ? AND `deleted` = 0",
                    Long.class, username));
        }

        PageResult<UserView> page = userService.listUsers(1, 3, prefix, UserRole.STUDENT, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.records()).extracting(UserView::id)
                .containsExactly(
                        Long.toString(ids.get(2)),
                        Long.toString(ids.get(1)),
                        Long.toString(ids.get(0)));
    }

    private RegisterRequest registration(String username) {
        return new RegisterRequest(username, "password8", "Integration User",
                null, null, null, null);
    }

    private String uniqueUsername() {
        String username = "t02_" + UUID.randomUUID().toString().replace("-", "");
        cleanupUsernames.add(username);
        return username;
    }

    private long activeCount(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE `username` = ? AND `deleted` = 0",
                Long.class, username);
    }

    private long deletedCount(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE `username` = ? AND `deleted` = 1",
                Long.class, username);
    }

    private int status(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT `status` FROM `user` WHERE `id` = ?", Integer.class, id);
    }

    private LocalDateTime updatedAt(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT `updated_at` FROM `user` WHERE `id` = ?",
                LocalDateTime.class, id);
    }

    private record RegistrationOutcome(int httpStatus, Integer code, String message) {
        static RegistrationOutcome created() {
            return new RegistrationOutcome(201, null, null);
        }

        static RegistrationOutcome conflict(BizException exception) {
            return new RegistrationOutcome(
                    exception.errorCode.httpStatus,
                    exception.errorCode.code,
                    exception.getMessage());
        }
    }

    private record UserDefaults(String role, int creditScore, int status, int deleted) {
    }

}
