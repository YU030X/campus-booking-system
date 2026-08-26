package com.yu030x.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingAdminReads;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Requires RESOURCE_MYSQL_URL (or the DB_* equivalents) pointing at a private
 * MySQL 8 schema; missing variables surface as an explicit failure, never a skip.
 */
@SpringBootTest(properties = {
        "booking.identity.enabled=true",
        "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class BookingActionsMysqlIntegrationTest {
    private static final String PREFIX = "codex-booking-actions-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BookingMapper bookingMapper;
    @Autowired
    private BookingActions actions;
    @Autowired
    private BookingAdminReads adminReads;

    private long categoryId;
    private long resourceId;
    private long ownerId;
    private long foreignId;
    private long bookingId;
    private String fixtureName;
    private LocalDate date;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
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
        date = LocalDate.now().plusDays(10);
        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1", Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,min_duration_minutes,"
                + "max_duration_minutes,status,deleted) VALUES (?,?,0,30,30,240,1,0)", categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                        + "VALUES (?,?,?,?,0)",
                resourceId, date.getDayOfWeek().getValue(), LocalTime.of(8, 0), LocalTime.of(22, 0));
        ownerId = insertUser("-owner");
        foreignId = insertUser("-foreign");
    }

    private long insertUser(String suffix) {
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) VALUES (?,?,?,?,1,0)",
                fixtureName + suffix, "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK", "学生", "STUDENT");
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, fixtureName + suffix);
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM booking_slot WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM booking WHERE user_id IN (?,?)", ownerId, foreignId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM `user` WHERE username LIKE ?", fixtureName + "%");
        jdbc.update("DELETE FROM resource_category WHERE id=? AND name=?", categoryId, fixtureName);
    }

    private long insertBooking(String status) {
        LocalDateTime start = LocalDateTime.of(date, LocalTime.of(14, 0));
        jdbc.update("INSERT INTO booking(booking_no,user_id,resource_id,start_time,end_time,purpose,"
                        + "attendee_count,status,deleted) VALUES (?,?,?,?,?,NULL,2,?,0)",
                uniqueBookingNo(status.charAt(0)), ownerId, resourceId,
                start, start.plusMinutes(30), status);
        return jdbc.queryForObject("SELECT id FROM booking WHERE user_id=? AND status=? ORDER BY id DESC LIMIT 1",
                Long.class, ownerId, status);
    }

    private String uniqueBookingNo(char tag) {
        return "B" + tag + Long.toString(System.nanoTime(), Character.MAX_RADIX);
    }

    private int slotCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?", Integer.class, id);
    }

    private void insertSlot(long id) {
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) "
                        + "SELECT resource_id,start_time,id FROM booking WHERE id=?", id);
    }

    @Test
    void pendingApprovalPageListsOnlyPendingRowsInAscendingOrderAndValidatesBounds() {
        long first = insertBooking("PENDING_APPROVAL");
        long second = insertBooking("PENDING_APPROVAL");
        insertBooking("CONFIRMED");
        insertBooking("REJECTED");

        PageResult<BookingView> page = adminReads.pagePendingApprovals(1, 10);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.records()).extracting(BookingView::id)
                .containsExactly(String.valueOf(first), String.valueOf(second));
        assertThat(page.records()).allSatisfy(view ->
                assertThat(view.status()).isEqualTo(BookingStatus.PENDING_APPROVAL));

        assertThrows(BizException.class, () -> adminReads.pagePendingApprovals(0, 10));
        assertThrows(BizException.class, () -> adminReads.pagePendingApprovals(1, 101));
    }

    @Test
    void conditionalApproveWinsOnceThenIsIdenticallyIdempotent() {
        bookingId = insertBooking("PENDING_APPROVAL");
        insertSlot(bookingId);

        assertEquals(1, bookingMapper.approvePending(bookingId, LocalDateTime.now()));
        assertEquals(0, bookingMapper.approvePending(bookingId, LocalDateTime.now()));
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        assertEquals(1, slotCount(bookingId));
    }

    @Test
    void winningRejectDeletesSlotsWhileApproveKeepsThem() {
        long pending = insertBooking("PENDING_APPROVAL");
        insertSlot(pending);
        BookingActionOutcome reject = actions.reject(pending);
        assertEquals(BookingActionOutcome.Result.WINNER, reject.result());
        assertEquals(BookingStatus.REJECTED, reject.booking().status());
        assertEquals(0, slotCount(pending));

        long approved = insertBooking("PENDING_APPROVAL");
        insertSlot(approved);
        BookingActionOutcome approve = actions.approve(approved);
        assertEquals(BookingActionOutcome.Result.WINNER, approve.result());
        assertEquals(1, slotCount(approved));
        jdbc.update("DELETE FROM booking_slot WHERE booking_id=?", approved);
    }

    @Test
    void cancelRequiresOwnershipActiveStatesAndStrictlyBeforeStart() {
        long pending = insertBooking("PENDING_APPROVAL");
        insertSlot(pending);
        LocalDateTime beforeStart = LocalDateTime.of(date, LocalTime.of(13, 30));
        BookingActionOutcome foreign = actions.cancel(pending, foreignId, beforeStart, null);
        assertEquals(BookingActionOutcome.Result.NOT_FOUND, foreign.result());
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, pending))
                .isEqualTo("PENDING_APPROVAL");
        assertEquals(1, slotCount(pending));

        BookingActionOutcome winner = actions.cancel(pending, ownerId, beforeStart, " 有事 ");
        assertEquals(BookingActionOutcome.Result.WINNER, winner.result());
        assertEquals(BookingStatus.CANCELLED, winner.booking().status());
        assertThat(jdbc.queryForObject(
                "SELECT cancel_reason FROM booking WHERE id=?", String.class, pending)).isEqualTo("有事");
        assertThat(jdbc.queryForObject("SELECT cancel_time FROM booking WHERE id=?", java.sql.Timestamp.class,
                pending)).isNotNull();
        assertEquals(0, slotCount(pending));

        BookingActionOutcome repeat = actions.cancel(pending, ownerId, beforeStart, "again");
        assertEquals(BookingActionOutcome.Result.ALREADY_COMPLETED, repeat.result());

        long checkedIn = insertBooking("CHECKED_IN");
        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.cancel(checkedIn, ownerId, beforeStart, null).result());
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, checkedIn))
                .isEqualTo("CHECKED_IN");
    }

    @Test
    void cancelExactlyAtStartOrLaterIsIllegalAndChangesNothing() {
        LocalDateTime start = LocalDateTime.of(date, LocalTime.of(14, 0));
        long booking = insertBooking("CONFIRMED");
        insertSlot(booking);

        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.cancel(booking, ownerId, start, null).result());
        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.cancel(booking, ownerId, start.plusMinutes(5), null).result());

        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT cancel_time FROM booking WHERE id=?",
                java.sql.Timestamp.class, booking)).isNull();
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM booking WHERE id=?", String.class, booking))
                .isNull();
        assertEquals(1, slotCount(booking));
    }

    @Test
    void checkInTransitionsOnlyConfirmedOwnerRowsAndIsIdempotent() {
        long confirmed = insertBooking("CONFIRMED");
        LocalDateTime checkinTime = LocalDateTime.of(date, LocalTime.of(13, 50));

        BookingActionOutcome winner = actions.checkIn(confirmed, ownerId, checkinTime);
        assertEquals(BookingActionOutcome.Result.WINNER, winner.result());
        assertEquals(checkinTime, winner.booking().checkinTime());
        LocalDateTime recorded = jdbc.queryForObject(
                "SELECT checkin_time FROM booking WHERE id=?", java.sql.Timestamp.class, confirmed).toLocalDateTime();
        assertEquals(checkinTime, recorded);

        assertEquals(BookingActionOutcome.Result.ALREADY_COMPLETED,
                actions.checkIn(confirmed, ownerId, checkinTime.plusMinutes(5)).result());
        assertEquals(checkinTime, jdbc.queryForObject(
                "SELECT checkin_time FROM booking WHERE id=?", java.sql.Timestamp.class, confirmed)
                .toLocalDateTime());

        assertEquals(BookingActionOutcome.Result.ILLEGAL_TRANSITION,
                actions.markNoShow(confirmed).result());
    }
}
