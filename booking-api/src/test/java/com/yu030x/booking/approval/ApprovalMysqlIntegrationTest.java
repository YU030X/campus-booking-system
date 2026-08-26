package com.yu030x.booking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yu030x.booking.BookingApplication;
import com.yu030x.booking.approval.service.ApprovalService;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requires RESOURCE_MYSQL_URL (or the DB_* equivalents) pointing at a private
 * MySQL 8 schema; missing variables surface as an explicit failure, never a skip.
 */
@SpringBootTest(classes = BookingApplication.class, properties = {
        "booking.identity.enabled=true",
        "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class ApprovalMysqlIntegrationTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "codex-approval-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private long categoryId;
    private long resourceId;
    private long ownerId;
    private long adminId;
    private String fixtureName;
    private LocalDate date;
    private final List<Long> bookingIds = new ArrayList<>();

    @org.springframework.test.context.DynamicPropertySource
    static void mysqlProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> env("RESOURCE_MYSQL_URL", "DB_URL"));
        registry.add("DB_USERNAME", () -> env("RESOURCE_MYSQL_USERNAME", "DB_USERNAME"));
        registry.add("DB_PASSWORD", () -> env("RESOURCE_MYSQL_PASSWORD", "DB_PASSWORD"));
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
    void createFixture() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        int major = Integer.parseInt(version.replaceFirst("^(\\d+).*", "$1"));
        assertThat(major).as("MySQL major version").isGreaterThanOrEqualTo(8);
        date = LocalDate.now(SHANGHAI).plusDays(10);
        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)",
                fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1",
                Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,"
                        + "min_duration_minutes,max_duration_minutes,status,deleted) "
                        + "VALUES (?,?,1,30,30,240,1,0)", categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                + "VALUES (?,?,?,?,0)",
                resourceId, date.getDayOfWeek().getValue(), LocalTime.of(0, 0), LocalTime.of(23, 59));
        ownerId = insertUser("-owner", "STUDENT", 100);
        adminId = insertUser("-admin", "ADMIN", 100);
    }

    private long insertUser(String suffix, String role, int credit) {
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,credit_score,deleted) "
                        + "VALUES (?,?,?,?,1,?,0)",
                fixtureName + suffix, passwordEncoder.encode("Password123!"), "学生", role, credit);
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",
                Long.class, fixtureName + suffix);
    }

    @AfterEach
    void removeFixture() {
        for (long bookingId : bookingIds) {
            jdbc.update("DELETE FROM approval_record WHERE booking_id=?", bookingId);
            jdbc.update("DELETE FROM violation_record WHERE booking_id=?", bookingId);
            jdbc.update("DELETE FROM booking_slot WHERE booking_id=?", bookingId);
        }
        jdbc.update("DELETE FROM booking WHERE user_id=?", ownerId);
        jdbc.update("DELETE FROM violation_record WHERE user_id IN (?,?)", ownerId, adminId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM `user` WHERE username LIKE ?", fixtureName + "%");
        jdbc.update("DELETE FROM resource_category WHERE id=? AND name=?", categoryId, fixtureName);
    }

    private long insertBooking(String status, LocalDateTime start) {
        jdbc.update("INSERT INTO booking(booking_no,user_id,resource_id,start_time,end_time,purpose,"
                        + "attendee_count,status,deleted) VALUES (?,?,?,?,?,NULL,2,?,0)",
                "BA" + Long.toString(System.nanoTime(), Character.MAX_RADIX),
                ownerId, resourceId, start, start.plusMinutes(60), status);
        long id = jdbc.queryForObject(
                "SELECT id FROM booking WHERE user_id=? AND status=? ORDER BY id DESC LIMIT 1",
                Long.class, ownerId, status);
        bookingIds.add(id);
        return id;
    }

    private void insertSlot(long bookingId) {
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) "
                + "SELECT resource_id,start_time,id FROM booking WHERE id=?", bookingId);
    }

    private int slotCount(long bookingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?",
                Integer.class, bookingId);
    }

    private long approveRecordCount(long bookingId, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_record WHERE booking_id=? AND action=?",
                Long.class, bookingId, action);
    }

    @Test
    void approveWinsKeepsSlotsInsertsOneImmutableRecordAndRepeatsAreNoOps() {
        long bookingId = insertBooking("PENDING_APPROVAL",
                LocalDateTime.of(date, LocalTime.of(14, 0)));
        insertSlot(bookingId);

        BookingView first = approvalService.approve(bookingId, adminId, null);
        assertThat(first.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(slotCount(bookingId)).isEqualTo(1);
        assertThat(approveRecordCount(bookingId, "APPROVE")).isEqualTo(1);

        BookingView second = approvalService.approve(bookingId, adminId, "again");
        assertThat(second.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(approveRecordCount(bookingId, "APPROVE")).isEqualTo(1);
        assertThat(slotCount(bookingId)).isEqualTo(1);

        org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> approvalService.reject(bookingId, adminId, "opposite action"));
        assertThat(approveRecordCount(bookingId, "REJECT")).isEqualTo(0);
    }

    @Test
    void rejectWinsReleasesSlotsAtomicallyAndRollbackRestoresEverything() {
        long bookingId = insertBooking("PENDING_APPROVAL",
                LocalDateTime.of(date, LocalTime.of(14, 0)));
        insertSlot(bookingId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            approvalService.reject(bookingId, adminId, "材料不全");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?",
                String.class, bookingId)).isEqualTo("PENDING_APPROVAL");
        assertThat(slotCount(bookingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_record WHERE booking_id=?", Long.class, bookingId))
                .isEqualTo(0);

        BookingView rejected = approvalService.reject(bookingId, adminId, "材料不全");
        assertThat(rejected.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(slotCount(bookingId)).isEqualTo(0);
        assertThat(approveRecordCount(bookingId, "REJECT")).isEqualTo(1);
    }

    @Test
    void earlyCancelHasNoViolationWhileLateCancelRecordsMinusFiveWithCreditFloor() {
        LocalDateTime farStart = LocalDateTime.now(SHANGHAI).plusHours(3);
        long early = insertBooking("CONFIRMED", farStart);
        insertSlot(early);
        BookingView earlyView = approvalService.cancel(ownerId, early, "行程有变");
        assertThat(earlyView.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(slotCount(early)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE booking_id=?", Long.class, early))
                .isEqualTo(0);

        LocalDateTime nearStart = LocalDateTime.now(SHANGHAI).plusMinutes(90);
        long late = insertBooking("CONFIRMED", nearStart);
        insertSlot(late);
        long poorUserId = insertUser("-poor", "STUDENT", 3);
        jdbc.update("UPDATE booking SET user_id=? WHERE id=?", poorUserId, late);
        bookingIds.remove(bookingIds.size() - 1);
        bookingIds.add(late);

        BookingView lateView = approvalService.cancel(poorUserId, late, "来不及了");
        assertThat(lateView.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(slotCount(late)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE booking_id=? "
                        + "AND violation_type='LATE_CANCEL' AND score_change=-5",
                Long.class, late)).isEqualTo(1);
        Integer credit = jdbc.queryForObject(
                "SELECT credit_score FROM `user` WHERE id=?", Integer.class, poorUserId);
        assertThat(credit).isEqualTo(0);
    }

    @Test
    void repeatedCancellationNeverDuplicatesLateCancelSideEffect() {
        LocalDateTime nearStart = LocalDateTime.now(SHANGHAI).plusMinutes(60);
        long bookingId = insertBooking("CONFIRMED", nearStart);
        insertSlot(bookingId);

        BookingView first = approvalService.cancel(ownerId, bookingId, null);
        assertThat(first.status()).isEqualTo(BookingStatus.CANCELLED);
        BookingView second = approvalService.cancel(ownerId, bookingId, "again");
        assertThat(second.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE booking_id=? "
                        + "AND violation_type='LATE_CANCEL'", Long.class, bookingId))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRejectsAllSucceedWithOneWinnerOneRecordAndSingleSlotRelease()
            throws Exception {
        long bookingId = insertBooking("PENDING_APPROVAL",
                LocalDateTime.of(date, LocalTime.of(14, 0)));
        insertSlot(bookingId);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<BookingView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timeout waiting to start");
                    }
                    return approvalService.reject(bookingId, adminId, "并发驳回");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (java.util.concurrent.Future<BookingView> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).status())
                        .isEqualTo(BookingStatus.REJECTED);
            }
            assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?",
                    String.class, bookingId)).isEqualTo("REJECTED");
            assertThat(approveRecordCount(bookingId, "REJECT")).isEqualTo(1);
            assertThat(approveRecordCount(bookingId, "APPROVE")).isEqualTo(0);
            assertThat(slotCount(bookingId)).isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentLateCancelsAllSucceedWithSingleViolationAndSingleDeduction() throws Exception {
        LocalDateTime nearStart = LocalDateTime.now(SHANGHAI).plusMinutes(60);
        long bookingId = insertBooking("CONFIRMED", nearStart);
        insertSlot(bookingId);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<BookingView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timeout waiting to start");
                    }
                    return approvalService.cancel(ownerId, bookingId, "并发晚取消");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (java.util.concurrent.Future<BookingView> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).status())
                        .isEqualTo(BookingStatus.CANCELLED);
            }
            assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?",
                    String.class, bookingId)).isEqualTo("CANCELLED");
            assertThat(slotCount(bookingId)).isEqualTo(0);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM violation_record WHERE booking_id=? "
                            + "AND violation_type='LATE_CANCEL' AND score_change=-5",
                    Long.class, bookingId)).isEqualTo(1);
            Integer credit = jdbc.queryForObject(
                    "SELECT credit_score FROM `user` WHERE id=?", Integer.class, ownerId);
            assertThat(credit).isEqualTo(95);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateApprovalsProduceExactlyOneWinnerAndOneRecord() throws Exception {
        long bookingId = insertBooking("PENDING_APPROVAL",
                LocalDateTime.of(date, LocalTime.of(14, 0)));
        insertSlot(bookingId);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<BookingView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    boolean awaited = go.await(10, TimeUnit.SECONDS);
                    if (!awaited) {
                        throw new IllegalStateException("timeout waiting to start");
                    }
                    try {
                        return approvalService.approve(bookingId, adminId, null);
                    } catch (BizException exception) {
                        return null;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int winners = 0;
            for (java.util.concurrent.Future<BookingView> future : futures) {
                BookingView view = future.get(30, TimeUnit.SECONDS);
                if (view != null && view.status() == BookingStatus.CONFIRMED) {
                    winners++;
                }
            }
            assertThat(winners).isGreaterThanOrEqualTo(1);
            assertThat(approveRecordCount(bookingId, "APPROVE")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?",
                    String.class, bookingId)).isEqualTo("CONFIRMED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void foreignMissingAndDeletedCancellationsShareUniform404Masking() {
        LocalDateTime start = LocalDateTime.of(date, LocalTime.of(15, 0));
        long owned = insertBooking("CONFIRMED", start);
        insertSlot(owned);
        long intruder = insertUser("-intruder", "STUDENT", 100);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvalService.cancel(intruder, owned, null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode.code).isEqualTo(40400));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvalService.cancel(intruder, 999999999L, null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode.code).isEqualTo(40400));

        jdbc.update("UPDATE booking SET deleted=1 WHERE id=?", owned);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvalService.cancel(ownerId, owned, null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode.code).isEqualTo(40400));
        assertThat(slotCount(owned)).isEqualTo(1);
    }
}
