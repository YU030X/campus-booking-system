package com.yu030x.booking.booking.mapper;

import com.yu030x.booking.booking.entity.BookingSlotEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BookingOccupancyMapper {
    @Insert("""
            <script>
            INSERT INTO booking_slot(resource_id,slot_time,booking_id) VALUES
            <foreach collection="slots" item="slot" separator=",">
                (#{resourceId},#{slot},#{bookingId})
            </foreach>
            </script>
            """)
    int batchInsert(
            @Param("resourceId") long resourceId,
            @Param("bookingId") long bookingId,
            @Param("slots") List<LocalDateTime> slots);

    @Select("SELECT id,resource_id,slot_time,booking_id,created_at FROM booking_slot "
            + "WHERE booking_id=#{bookingId} ORDER BY slot_time ASC,id ASC")
    List<BookingSlotEntity> selectByBookingId(@Param("bookingId") long bookingId);

    @Select("SELECT COUNT(*) FROM booking_slot WHERE booking_id=#{bookingId}")
    long countByBookingId(@Param("bookingId") long bookingId);

    @Delete("DELETE FROM booking_slot WHERE booking_id=#{bookingId}")
    int deleteByBookingId(@Param("bookingId") long bookingId);
}
