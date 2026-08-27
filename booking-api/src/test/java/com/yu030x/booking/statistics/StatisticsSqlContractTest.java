package com.yu030x.booking.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.statistics.mapper.StatisticsMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * Static SQL contract: reads the MyBatis annotations directly, so the frozen
 * aggregate semantics hold without touching a database.
 */
class StatisticsSqlContractTest {

    private String sqlOf(String method, Class<?>... parameterTypes) throws Exception {
        Method target = StatisticsMapper.class.getMethod(method, parameterTypes);
        Select select = target.getAnnotation(Select.class);
        assertThat(select).as("statistics SQL must stay annotated on the mapper").isNotNull();
        return String.join("\n", select.value());
    }

    @Test
    void usageQueryUsesRecursiveCalendarWeekdayRulesAndBothClosureScopes() throws Exception {
        String sql = sqlOf("selectResourceUsage", String.class, String.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class);

        assertThat(sql).contains("WITH RECURSIVE calendar");
        // Asia/Shanghai day-of-week mapping 1=Mon..7=Sun via WEEKDAY()+1.
        assertThat(sql).contains("WEEKDAY(c.day) + 1");
        // Global closure (resource_id = 0) and per-resource closure both zero the day.
        assertThat(sql).contains("gc.resource_id = 0");
        assertThat(sql).contains("sc.resource_id = dr.resource_id");
        // Rules contribute minutes only when alive; negative guards keep sums sane.
        assertThat(sql).contains("rtr.deleted = 0");
        assertThat(sql).contains("GREATEST(0");
        // Frozen slot occupancy: every row inside the half-open window is exactly 30 minutes.
        assertThat(sql).contains("COUNT(*) * 30");
        assertThat(sql).contains("bs.slot_time >= #{rangeStart}");
        assertThat(sql).contains("bs.slot_time < #{rangeEndEx}");
        // Booking counts share the window and honour soft delete.
        assertThat(sql).contains("b.deleted = 0");
        assertThat(sql).contains("b.start_time >= #{rangeStart}");
        assertThat(sql).contains("b.start_time < #{rangeEndEx}");
        // Every living resource appears with stable numeric ordering.
        assertThat(sql).contains("r.deleted = 0");
        assertThat(sql).contains("ORDER BY r.id ASC");
        // Parameters are bound by name for dates too.
        assertThat(sql).contains("#{fromDate}");
        assertThat(sql).contains("#{toDate}");
    }

    @Test
    void statusQueryAggregatesOnStartTimeWindowWithFrozenOrdering() throws Exception {
        String sql = sqlOf("selectBookingStatusCounts",
                java.time.LocalDateTime.class, java.time.LocalDateTime.class);

        assertThat(sql).contains("b.deleted = 0");
        assertThat(sql).contains("b.start_time >= #{rangeStart}");
        assertThat(sql).contains("b.start_time < #{rangeEndEx}");
        assertThat(sql).contains("FIELD(b.status, 'PENDING_APPROVAL', 'CONFIRMED', 'CHECKED_IN', "
                + "'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW')");
    }
}
