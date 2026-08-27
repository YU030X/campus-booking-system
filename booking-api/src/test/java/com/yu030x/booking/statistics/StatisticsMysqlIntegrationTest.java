package com.yu030x.booking.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in REAL MySQL 8 evidence for tasks 4.3/4.4: aggregate correctness against
 * actual data (rules weekday matching, global resource_id=0 closures, per
 * resource closures, frozen 30-minute slot occupancy) plus MySQL EXPLAIN plans
 * proving index-only access paths.
 *
 * STATUS: this skeleton is written ONLY; it was neither compiled nor executed
 * in this session and MUST NOT be quoted as passing evidence until an owned
 * verification run records BOOKING_MYSQL8_TEST=true, DB_URL, DB_USERNAME,
 * DB_PASSWORD plus the exact commands and raw EXPLAIN output below.
 */
@SpringBootTest(classes = BookingApplication.class,
        properties = {"booking.statistics.enabled=true",
                "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@EnabledIfEnvironmentVariable(named = "BOOKING_MYSQL8_TEST", matches = "(?i:true)")
class StatisticsMysqlIntegrationTest {
    private static final String USAGE_AGGREGATE = """
            WITH RECURSIVE calendar(day) AS (
                SELECT DATE(?)
                UNION ALL
                SELECT day + INTERVAL 1 DAY FROM calendar WHERE day < DATE(?)
            ),
            daily_rule AS (
                SELECT rtr.resource_id AS resource_id,
                       rtr.day_of_week AS day_of_week,
                       CAST(SUM(GREATEST(0,
                           TIME_TO_SEC(rtr.end_time) - TIME_TO_SEC(rtr.start_time))) / 60 AS SIGNED) AS minutes
                FROM resource_time_rule rtr
                WHERE rtr.deleted = 0
                GROUP BY rtr.resource_id, rtr.day_of_week
            ),
            day_minutes AS (
                SELECT dr.resource_id AS resource_id, c.day AS day,
                       CASE WHEN EXISTS (SELECT 1 FROM resource_closure gc
                                WHERE gc.closure_date = c.day AND gc.resource_id = 0)
                             OR EXISTS (SELECT 1 FROM resource_closure sc
                                WHERE sc.closure_date = c.day AND sc.resource_id = dr.resource_id)
                            THEN 0 ELSE dr.minutes END AS minutes
                FROM calendar c
                JOIN daily_rule dr ON dr.day_of_week = WEEKDAY(c.day) + 1
            ),
            denominator AS (
                SELECT resource_id, SUM(minutes) AS schedulable_minutes
                FROM day_minutes GROUP BY resource_id
            ),
            occupied AS (
                SELECT bs.resource_id AS resource_id, COUNT(*) * 30 AS occupied_slot_minutes
                FROM booking_slot bs
                WHERE bs.slot_time >= ? AND bs.slot_time < ?
                GROUP BY bs.resource_id
            ),
            counts AS (
                SELECT b.resource_id AS resource_id, COUNT(*) AS total_count,
                       CAST(SUM(b.status = 'COMPLETED') AS SIGNED) AS completed_count,
                       CAST(SUM(b.status = 'CANCELLED') AS SIGNED) AS cancelled_count,
                       CAST(SUM(b.status = 'NO_SHOW')   AS SIGNED) AS no_show_count
                FROM booking b
                WHERE b.deleted = 0 AND b.start_time >= ? AND b.start_time < ?
                GROUP BY b.resource_id
            )
            SELECT r.id AS resource_id, r.name AS resource_name,
                   COALESCE(c.total_count, 0) AS booking_count,
                   COALESCE(c.completed_count, 0) AS completed_count,
                   COALESCE(c.cancelled_count, 0) AS cancelled_count,
                   COALESCE(c.no_show_count, 0) AS no_show_count,
                   COALESCE(o.occupied_slot_minutes, 0) AS occupied_slot_minutes,
                   d.schedulable_minutes AS schedulable_minutes
            FROM resource r
            LEFT JOIN counts c ON c.resource_id = r.id
            LEFT JOIN occupied o ON o.resource_id = r.id
            LEFT JOIN denominator d ON d.resource_id = r.id
            WHERE r.deleted = 0
            ORDER BY r.id ASC
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<Long> resourceIds = new ArrayList<>();
    private final List<Long> bookingIds = new ArrayList<>();

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
            jdbcTemplate.update("DELETE FROM booking WHERE id = ?", bookingId);
        }
        for (Long resourceId : resourceIds) {
            jdbcTemplate.update("DELETE FROM resource_time_rule WHERE resource_id = ?", resourceId);
            jdbcTemplate.update("DELETE FROM resource_closure WHERE resource_id IN (?, 0)", resourceId);
            jdbcTemplate.update("DELETE FROM resource WHERE id = ?", resourceId);
        }
    }

    @Test
    void aggregatesMatchAHandBuiltFixtureIncludingClosuresAndSlotOccupancy() {
        long musicRoom = seedResource("T12 琴房");
        // Monday..Friday each 08:00..10:00 => 120 rule minutes/day.
        for (int dow = 1; dow <= 5; dow++) {
            jdbcTemplate.update("INSERT INTO resource_time_rule "
                    + "(resource_id, day_of_week, start_time, end_time, deleted) VALUES (?, ?, '08:00', '10:00', 0)",
                    musicRoom, dow);
        }
        long deadRoom = seedResource("T12 停用规则房");
        // One week window Mon 2026-08-03 .. Sun 2026-08-09.
        String from = "2026-08-03";
        String to = "2026-08-09";
        // Wednesday global holiday zeroes every resource that day.
        jdbcTemplate.update(
                "INSERT INTO resource_closure (resource_id, closure_date) VALUES (0, '2026-08-05')");
        // Thursday specific closure zeroes only the music room.
        jdbcTemplate.update(
                "INSERT INTO resource_closure (resource_id, closure_date) VALUES (?, '2026-08-06')",
                musicRoom);

        long completed = seedBooking(musicRoom, LocalDateTime.parse("2026-08-03T09:00"),
                LocalDateTime.parse("2026-08-03T11:00"), "COMPLETED");
        seedSlots(musicRoom, completed, 4); // 120 minutes of slots inside 09:00..11:00
        long cancelled = seedBooking(musicRoom, LocalDateTime.parse("2026-08-07T09:00"),
                LocalDateTime.parse("2026-08-07T10:30"), "CANCELLED");
        seedSlots(musicRoom, cancelled, 3); // slots still exist before release semantics fire
        long noShow = seedBooking(deadRoom, LocalDateTime.parse("2026-08-03T08:00"),
                LocalDateTime.parse("2026-08-03T09:00"), "NO_SHOW");
        seedSlots(deadRoom, noShow, 2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(USAGE_AGGREGATE,
                from, to,
                LocalDateTime.parse("2026-08-03T00:00"), LocalDateTime.parse("2026-08-10T00:00"),
                LocalDateTime.parse("2026-08-03T00:00"), LocalDateTime.parse("2026-08-10T00:00"));

        Map<String, Object> musicRow = rowOf(rows, musicRoom);
        // Denominator: Wed (global closure) and Thu (specific closure) contribute
        // zero; Mon+Tue+Fri keep 3 x 120 rule minutes.
        assertThat(musicRow.get("schedulable_minutes")).isEqualTo(360L);
        assertThat(musicRow.get("occupied_slot_minutes")).isEqualTo(210L); // 7 slots x 30
        assertThat(musicRow.get("booking_count")).isEqualTo(2L);
        assertThat(musicRow.get("completed_count")).isEqualTo(1L);
        assertThat(musicRow.get("cancelled_count")).isEqualTo(1L);
        assertThat(musicRow.get("no_show_count")).isEqualTo(0L);

        Map<String, Object> deadRow = rowOf(rows, deadRoom);
        assertThat(deadRow.get("occupied_slot_minutes")).isEqualTo(60L); // 2 x 30
        assertThat(deadRow.get("no_show_count")).isEqualTo(1L);
        assertThat(deadRow.get("booking_count")).isEqualTo(1L);
    }

    @Test
    void explainEvidenceReliesOnlyOnExistingIndexesAndIsRecordedVerbatim() {
        // EXPLAIN plans are captured verbatim as acceptance artifacts; exact
        // access types are environment-dependent and asserted loosely here.
        recordPlan("EXPLAIN " + USAGE_AGGREGATE, new Object[]{
                "2026-08-03", "2026-08-09",
                LocalDateTime.parse("2026-08-03T00:00"), LocalDateTime.parse("2026-08-10T00:00"),
                LocalDateTime.parse("2026-08-03T00:00"), LocalDateTime.parse("2026-08-10T00:00")});
    }

    private void recordPlan(String sql, Object[] args) {
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(sql, args);
        for (Map<String, Object> line : plan) {
            System.out.println("[statistics-explain] " + line);
        }
        assertThat(plan).isNotEmpty();
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, long resourceId) {
        return rows.stream()
                .filter(row -> Long.valueOf(String.valueOf(row.get("resource_id"))) == resourceId)
                .findFirst()
                .orElseThrow();
    }

    private long seedResource(String name) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO resource (category_id, name, location, max_advance_days,
                                      min_duration_minutes, max_duration_minutes, status, deleted)
                VALUES (0, ?, ?, 7, 30, 480, 1, 0)
                """, name + suffix, "T12 statistics wing");
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM resource WHERE name = ?", Long.class, name + suffix);
        resourceIds.add(id);
        return id;
    }

    private long seedBooking(long resourceId, LocalDateTime start, LocalDateTime end, String status) {
        String bookingNo = "BK" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbcTemplate.update("""
                INSERT INTO booking (booking_no, user_id, resource_id, start_time, end_time, status)
                VALUES (?, 900001, ?, ?, ?, ?)
                """, bookingNo, resourceId, start, end, status);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM booking WHERE booking_no = ?", Long.class, bookingNo);
        bookingIds.add(id);
        return id;
    }

    private void seedSlots(long resourceId, long bookingId, int count) {
        for (int index = 0; index < count; index++) {
            jdbcTemplate.update("""
                    INSERT INTO booking_slot (resource_id, slot_time, booking_id) VALUES (?, ?, ?)
                    """, resourceId, LocalDateTime.parse("2026-08-01T08:00").plusMinutes(30L * index),
                    bookingId);
        }
    }
}
