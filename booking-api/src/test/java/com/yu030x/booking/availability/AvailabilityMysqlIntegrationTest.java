package com.yu030x.booking.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Requires RESOURCE_MYSQL_URL (or the DB_* equivalents) and a MySQL 8+ schema. */
@SpringBootTest
class AvailabilityMysqlIntegrationTest {
    private static final String PREFIX = "codex-availability-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AvailabilityService availability;

    private long categoryId;
    private long resourceId;
    private LocalDate tomorrow;
    private long bookingId;
    private String fixtureName;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        String url = env("RESOURCE_MYSQL_URL", "DB_URL");
        String username = env("RESOURCE_MYSQL_USERNAME", "DB_USERNAME");
        String password = env("RESOURCE_MYSQL_PASSWORD", "DB_PASSWORD");
        registry.add("DB_URL", () -> url);
        registry.add("DB_USERNAME", () -> username);
        registry.add("DB_PASSWORD", () -> password);
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
        assertThat(version).isNotBlank();
        int major = Integer.parseInt(version.replaceFirst("^(\\d+).*", "$1"));
        assertThat(major).as("MySQL major version").isGreaterThanOrEqualTo(8);
        tomorrow = LocalDate.now().plusDays(10);
        while (closureExists(0, tomorrow) || closureExists(0, tomorrow.plusDays(1))) {
            tomorrow = tomorrow.plusDays(1);
        }
        fixtureName = PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", fixtureName);
        categoryId = jdbc.queryForObject("SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1",
                Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,min_duration_minutes,"
                + "max_duration_minutes,status,deleted) VALUES (?,?,0,30,30,120,1,0)", categoryId, fixtureName);
        resourceId = jdbc.queryForObject("SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        bookingId = 900000000L + resourceId;
    }

    @AfterEach
    void removeFixture() {
        if (resourceId != 0) {
            jdbc.update("DELETE FROM booking_slot WHERE resource_id=? AND booking_id=?", resourceId, bookingId);
            jdbc.update("DELETE FROM resource_closure WHERE resource_id=? AND closure_date=?", resourceId,
                    tomorrow.plusDays(1));
            jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
            jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        }
        if (tomorrow != null) {
            jdbc.update("DELETE FROM resource_closure WHERE resource_id=0 AND closure_date=? AND reason=?",
                    tomorrow, fixtureName);
        }
        if (categoryId != 0) {
            jdbc.update("DELETE FROM resource_category WHERE id=? AND name=?", categoryId, fixtureName);
        }
    }

    @Test
    void readsPersistedRulesClosuresAndOccupancyWithoutChangingBookingSchema() {
        int dow = tomorrow.getDayOfWeek().getValue();
        jdbc.update("INSERT INTO resource_time_rule(resource_id,day_of_week,start_time,end_time,deleted) VALUES "
                + "(?,?,?,?,0),(?,?,?,?,0),(?,?,?,?,0)", resourceId, dow, LocalTime.of(8, 0), LocalTime.of(9, 0),
                resourceId, dow, LocalTime.of(10, 0), LocalTime.of(11, 0), resourceId, dow, LocalTime.of(11, 0),
                LocalTime.of(12, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='booking_slot' AND REFERENCED_TABLE_NAME IS NOT NULL",
                Integer.class)).isZero();
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES (?,?,?)", resourceId,
                LocalDateTime.of(tomorrow, LocalTime.of(10, 0)), bookingId);
        List<String> slotsBefore = bookingRows();
        List<String> indexBefore = indexDefinition();
        assertThat(indexBefore).as("uk_resource_slot definition").isNotEmpty();
        assertThat(indexBefore).allMatch(row -> row.startsWith("uk_resource_slot|0|"));

        AvailabilityVO result = availability.get(resourceId, tomorrow);

        assertThat(result.slots()).extracting(AvailabilityVO.SlotVO::startTime)
                .containsExactly("08:00", "08:30", "10:00", "10:30", "11:00", "11:30");
        assertThat(result.slots().get(2).available()).isFalse();
        assertThat(assertThrows(BizException.class, () -> availability.get(resourceId, tomorrow.plusDays(21))).errorCode)
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(slotsBefore).isEqualTo(bookingRows());
        assertThat(indexBefore).isEqualTo(indexDefinition());
    }

    @Test
    void enforcesLogicalDeleteStatusesAndBothClosureScopes() {
        jdbc.update("UPDATE resource SET status=0 WHERE id=?", resourceId);
        assertThat(assertThrows(BizException.class, () -> availability.get(resourceId, tomorrow)).errorCode)
                .isEqualTo(ErrorCode.RESOURCE_ERROR);
        jdbc.update("UPDATE resource SET status=2 WHERE id=?", resourceId);
        assertThat(assertThrows(BizException.class, () -> availability.get(resourceId, tomorrow)).errorCode)
                .isEqualTo(ErrorCode.RESOURCE_ERROR);
        jdbc.update("UPDATE resource SET status=1,deleted=1 WHERE id=?", resourceId);
        assertThat(assertThrows(BizException.class, () -> availability.get(resourceId, tomorrow)).errorCode)
                .isEqualTo(ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE resource SET deleted=0,max_advance_days=0 WHERE id=?", resourceId);
        assertThat(assertThrows(BizException.class, () -> availability.get(resourceId, tomorrow)).errorCode)
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        jdbc.update("UPDATE resource SET max_advance_days=30 WHERE id=?", resourceId);
        jdbc.update("INSERT INTO resource_closure(resource_id,closure_date,reason) VALUES (0,?,?), (?, ?, ?)",
                tomorrow, fixtureName, resourceId, tomorrow.plusDays(1), fixtureName);
        assertThat(availability.get(resourceId, tomorrow).slots()).isEmpty();
        assertThat(availability.get(resourceId, tomorrow.plusDays(1)).slots()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_closure WHERE resource_id=0 AND closure_date=? "
                + "AND reason=?", Integer.class, tomorrow, fixtureName)).isEqualTo(1);
    }

    private List<String> indexDefinition() {
        return jdbc.query("SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='booking_slot' AND index_name='uk_resource_slot' "
                + "ORDER BY seq_in_index", (rs, row) -> rs.getString(1) + "|" + rs.getInt(2) + "|" + rs.getInt(3)
                + "|" + rs.getString(4));
    }

    private boolean closureExists(long scope, LocalDate date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM resource_closure WHERE resource_id=? AND closure_date=?",
                Integer.class, scope, date) > 0;
    }

    private List<String> bookingRows() {
        return jdbc.query("SELECT resource_id,slot_time,booking_id FROM booking_slot WHERE resource_id=? ORDER BY id",
                (rs, row) -> rs.getLong(1) + "|" + rs.getTimestamp(2) + "|" + rs.getLong(3), resourceId);
    }
}
