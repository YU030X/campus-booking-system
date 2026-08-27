package com.yu030x.booking.availability;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BookingSlotMapper {
    @Select("""
            SELECT slot_time
            FROM booking_slot
            WHERE resource_id = #{resourceId}
              AND slot_time >= #{start}
              AND slot_time < #{end}
            """)
    List<LocalDateTime> find(
            @Param("resourceId") long resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
