package com.yu030x.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.BookingCreator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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
class BookingMysqlIntegrationTest {
    private static final String PREFIX = "codex-booking-it-";
    private static final long FAKE_OTHER_BOOKING = 990000001L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BookingCreator creator;
    @Autowired
    private BookingMapper bookingMapper;

    private long categoryId;
    private long resourceId;
    private long userId;
    private long otherUserId;
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
        while (!jdbc.queryForList("SELECT closure_date FROM resource_closure WHERE closure_date=?",
                LocalDate.class, date).isEmpty()) {
            date = date.plusDays(1);
        }
        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1", Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,min_duration_minutes,"
                + "max_duration_minutes,status,deleted) VALUES (?,?,0,30,30,240,1,0)", categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        int dow = date.getDayOfWeek().getValue();
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) "
                        + "VALUES (?,?,?,?,0)",
                resourceId, dow, LocalTime.of(8, 0), LocalTime.of(22, 0));
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) VALUES (?,?,?,?,1,0)",
                fixtureName + "-stu", "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK", "学生甲", "STUDENT");
        userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, fixtureName + "-stu");
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) VALUES (?,?,?,?,1,0)",
                fixtureName + "-oth", "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK", "学生乙", "STUDENT");
        otherUserId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",
                Long.class, fixtureName + "-oth");
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM booking_slot WHERE resource_id=? OR booking_id IN "
                + "(SELECT t.id FROM (SELECT id FROM booking WHERE user_id IN (?,?)) t)",
                resourceId, userId, otherUserId);
        jdbc.update("DELETE FROM booking WHERE user_id IN (?,?)", userId, otherUserId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM blacklist WHERE user_id IN (?,?)", userId, otherUserId);
        jdbc.update("DELETE FROM `user` WHERE username LIKE ?", fixtureName + "%");
        jdbc.update("DELETE FROM resource_category WHERE id=? AND name=?", categoryId, fixtureName);
    }

    private CreateBookingRequest request(LocalDateTime start, LocalDateTime end) {
        return new CreateBookingRequest(String.valueOf(resourceId), start, end, " 讨论 ", 2);
    }

    private LocalDateTime at(String time) {
        return LocalDateTime.of(date, LocalTime.parse(time));
    }

    @Test
    void createPersistsOneBookingWithEverySlotAndChoosesInitialState() {
        BookingView confirmed = creator.create(userId, request(at("14:00"), at("16:00")));

        assertThat(confirmed.status().name()).isEqualTo("CONFIRMED");
        assertThat(confirmed.purpose()).isEqualTo("讨论");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE id=?", Long.class,
                Long.parseLong(confirmed.id()))).isEqualTo(1);
        assertThat(slotTimes(Long.parseLong(confirmed.id()))).containsExactly(
                at("14:00"), at("14:30"), at("15:00"), at("15:30"));

        jdbc.update("UPDATE resource SET need_approval=1 WHERE id=?", resourceId);
        BookingView pending = creator.create(userId, request(at("18:00"), at("18:30")));
        assertThat(pending.status().name()).isEqualTo("PENDING_APPROVAL");
        assertThat(slotTimes(Long.parseLong(pending.id()))).containsExactly(at("18:00"));
    }

    @Test
    void laterSlotConflictRollsBackBookingAndAllSlots() {
        LocalDateTime conflictTime = at("14:00");
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?)",
                resourceId, conflictTime, FAKE_OTHER_BOOKING);
        long slotsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE resource_id=?",
                Long.class, resourceId);

        BizException exception = assertThrows(BizException.class,
                () -> creator.create(userId, request(conflictTime, conflictTime.plusHours(1))));

        assertThat(exception.errorCode).isEqualTo(ErrorCode.BOOKING_ERROR);
        assertThat(exception.getMessage()).isEqualTo(BookingMessages.SLOT_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE user_id=? AND start_time=?",
                Long.class, userId, conflictTime)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE resource_id=?",
                Long.class, resourceId)).isEqualTo(slotsBefore);

        jdbc.update("DELETE FROM booking_slot WHERE resource_id=? AND booking_id=?", resourceId, FAKE_OTHER_BOOKING);
    }

    @Test
    void frozenUniqueKeyStillRejectsDuplicateResourceSlotPairs() {
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?)",
                resourceId, at("09:00"), FAKE_OTHER_BOOKING);
        assertThat(uniqueKeyColumns()).containsExactly("resource_id", "slot_time");
        assertThat(uniqueKeyNonUnique()).isEqualTo(0);

        assertThrows(DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?)",
                        resourceId, at("09:00"), FAKE_OTHER_BOOKING + 1));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_slot WHERE resource_id=? AND slot_time=?",
                Long.class, resourceId, at("09:00"))).isEqualTo(1);

        jdbc.update("DELETE FROM booking_slot WHERE resource_id=? AND booking_id=?", resourceId, FAKE_OTHER_BOOKING);
    }

    @Test
    void listAndDetailMaskForeignDeletedOrMissingBookings() {
        BookingView mine = creator.create(userId, request(at("14:00"), at("14:30")));

        assertThat(bookingMapper.selectActiveByIdAndUser(Long.parseLong(mine.id()), otherUserId)).isNull();

        PageResult<BookingView> page = bookingList(otherUserId, 1, 10);
        assertThat(page.total()).isZero();

        List<BookingView> minePage = bookingList(userId, 1, 10).records();
        assertThat(minePage).extracting(BookingView::id).containsExactly(mine.id());

        jdbc.update("DELETE FROM booking WHERE id=?", Long.parseLong(mine.id()));
    }

    @Test
    void deletedBookingIsMaskedAsNotFoundLikeForeignRecords() {
        BookingView mine = creator.create(userId, request(at("14:00"), at("14:30")));
        long id = Long.parseLong(mine.id());
        jdbc.update("UPDATE booking SET deleted=1 WHERE id=?", id);

        assertThat(bookingMapper.selectActiveByIdAndUser(id, userId)).isNull();

        jdbc.update("DELETE FROM booking WHERE id=?", id);
    }

    private PageResult<BookingView> bookingList(long userId, int pageNumber, int pageSize) {
        var page = bookingMapper.selectUserPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNumber, pageSize),
                userId, null);
        return new PageResult<>(pageNumber, pageSize, page.getTotal(),
                page.getRecords().stream().map(BookingView::from).toList());
    }

    private List<LocalDateTime> slotTimes(long bookingId) {
        return jdbc.query("SELECT slot_time FROM booking_slot WHERE booking_id=? ORDER BY slot_time",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime(), bookingId);
    }

    private List<String> uniqueKeyColumns() {
        return jdbc.query("SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name='booking_slot' "
                        + "AND index_name='uk_resource_slot' ORDER BY seq_in_index",
                (rs, row) -> rs.getString(1));
    }

    private int uniqueKeyNonUnique() {
        return jdbc.queryForObject("SELECT MIN(non_unique) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='booking_slot' "
                + "AND index_name='uk_resource_slot'", Integer.class);
    }
}
