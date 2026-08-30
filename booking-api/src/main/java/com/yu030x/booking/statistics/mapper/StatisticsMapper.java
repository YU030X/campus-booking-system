package com.yu030x.booking.statistics.mapper;

import com.yu030x.booking.statistics.projection.ResourceUsageRow;
import com.yu030x.booking.statistics.projection.StatusCountRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only aggregate SQL for the statistics slice. All tables are read with
 * their exact DDL columns (sql/V002, V003): no migrations and no new indexes;
 * only idx_resource_day, uk_resource_date, uk_resource_slot/idx_booking_slot
 * access paths plus idx_resource_start / idx_status_start on booking.
 *
 * <p>Parameters are explicitly bound: {@code rangeStart} is the inclusive
 * first-day 00:00 LocalDateTime, {@code rangeEndEx} the exclusive day after the
 * last inclusive day, and {@code fromDate}/{@code toDate} canonical
 * yyyy-MM-dd strings for the recursive calendar seed.</p>
 */
@Mapper
public interface StatisticsMapper {

    /**
     * Per-resource usage aggregate, resourceId ascending. Occupied minutes use
     * the frozen booking/slot semantics: each booking_slot row inside the
     * half-open window contributes exactly 30 minutes. The denominator sums,
     * for every calendar date, the day's deleted=0 resource_time_rule minutes
     * (WEEKDAY()+1 matches 1=Mon..7=Sun); any matching resource_closure row for
     * that date — global resource_id=0 or the specific resource — zeroes the
     * whole day. Resources without rules simply sum to zero.
     */
    @Select("""
            WITH RECURSIVE calendar(day) AS (
                SELECT DATE(#{fromDate})
                UNION ALL
                SELECT day + INTERVAL 1 DAY FROM calendar WHERE day < DATE(#{toDate})
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
                SELECT dr.resource_id AS resource_id,
                       c.day          AS day,
                       CASE WHEN EXISTS (
                                SELECT 1 FROM resource_closure gc
                                WHERE gc.closure_date = c.day AND gc.resource_id = 0)
                             OR EXISTS (
                                SELECT 1 FROM resource_closure sc
                                WHERE sc.closure_date = c.day AND sc.resource_id = dr.resource_id)
                            THEN 0 ELSE dr.minutes END AS minutes
                FROM calendar c
                JOIN daily_rule dr ON dr.day_of_week = WEEKDAY(c.day) + 1
            ),
            denominator AS (
                SELECT resource_id, SUM(minutes) AS schedulable_minutes
                FROM day_minutes
                GROUP BY resource_id
            ),
            occupied AS (
                SELECT bs.resource_id AS resource_id,
                       COUNT(*) * 30  AS occupied_slot_minutes
                FROM booking_slot bs
                WHERE bs.slot_time >= #{rangeStart} AND bs.slot_time < #{rangeEndEx}
                GROUP BY bs.resource_id
            ),
            counts AS (
                SELECT b.resource_id                              AS resource_id,
                       COUNT(*)                                   AS total_count,
                       CAST(SUM(b.status = 'COMPLETED') AS SIGNED) AS completed_count,
                       CAST(SUM(b.status = 'CANCELLED') AS SIGNED) AS cancelled_count,
                       CAST(SUM(b.status = 'NO_SHOW')   AS SIGNED) AS no_show_count
                FROM booking b
                WHERE b.deleted = 0
                  AND b.start_time >= #{rangeStart}
                  AND b.start_time < #{rangeEndEx}
                GROUP BY b.resource_id
            )
            SELECT r.id                    AS resource_id,
                   r.name                  AS resource_name,
                   COALESCE(c.total_count, 0)         AS booking_count,
                   COALESCE(c.completed_count, 0)     AS completed_count,
                   COALESCE(c.cancelled_count, 0)     AS cancelled_count,
                   COALESCE(c.no_show_count, 0)       AS no_show_count,
                   COALESCE(o.occupied_slot_minutes, 0) AS occupied_slot_minutes,
                   d.schedulable_minutes              AS schedulable_minutes
            FROM resource r
            LEFT JOIN counts c ON c.resource_id = r.id
            LEFT JOIN occupied o ON o.resource_id = r.id
            LEFT JOIN denominator d ON d.resource_id = r.id
            WHERE r.deleted = 0
            ORDER BY r.id ASC
            """)
    List<ResourceUsageRow> selectResourceUsage(@Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEndEx") LocalDateTime rangeEndEx);

    /** Status counts over booking.start_time in the same half-open window, deleted=0 only. */
    @Select("""
            SELECT b.status AS status, COUNT(*) AS count_value
            FROM booking b
            WHERE b.deleted = 0
              AND b.start_time >= #{rangeStart}
              AND b.start_time < #{rangeEndEx}
            GROUP BY b.status
            ORDER BY FIELD(b.status, 'PENDING_APPROVAL', 'CONFIRMED', 'CHECKED_IN',
                           'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW')
            """)
    List<StatusCountRow> selectBookingStatusCounts(@Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEndEx") LocalDateTime rangeEndEx);
}
