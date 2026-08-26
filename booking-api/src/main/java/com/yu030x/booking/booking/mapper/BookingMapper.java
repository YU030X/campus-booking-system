package com.yu030x.booking.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yu030x.booking.booking.entity.BookingEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BookingMapper extends BaseMapper<BookingEntity> {
    String ACTIVE_STATUSES = "('PENDING_APPROVAL','CONFIRMED','CHECKED_IN')";

    @Select("""
            <script>
            SELECT id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,
                   checkin_time,cancel_time,cancel_reason,deleted,created_at,updated_at
            FROM booking
            WHERE deleted=0 AND user_id=#{userId}
            <if test="status != null">AND status=#{status}</if>
            ORDER BY created_at DESC,id DESC
            </script>
            """)
    IPage<BookingEntity> selectUserPage(
            IPage<BookingEntity> page,
            @Param("userId") long userId,
            @Param("status") String status);

    @Select("SELECT id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,"
            + "checkin_time,cancel_time,cancel_reason,deleted,created_at,updated_at "
            + "FROM booking WHERE deleted=0 AND id=#{id} AND user_id=#{userId}")
    BookingEntity selectActiveByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,"
            + "checkin_time,cancel_time,cancel_reason,deleted,created_at,updated_at "
            + "FROM booking WHERE deleted=0 AND id=#{id}")
    BookingEntity selectActiveById(@Param("id") long id);

    @Update("UPDATE booking SET status='CONFIRMED',updated_at=#{now} "
            + "WHERE id=#{id} AND deleted=0 AND status='PENDING_APPROVAL'")
    int approvePending(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE booking SET status='REJECTED',updated_at=#{now} "
            + "WHERE id=#{id} AND deleted=0 AND status='PENDING_APPROVAL'")
    int rejectPending(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE booking SET status='CANCELLED',cancel_time=#{now},cancel_reason=#{reason},updated_at=#{now} "
            + "WHERE id=#{id} AND user_id=#{userId} AND deleted=0 "
            + "AND status IN ('PENDING_APPROVAL','CONFIRMED')")
    int cancelActiveByOwner(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    @Update("UPDATE booking SET status='CHECKED_IN',checkin_time=#{checkinTime},updated_at=#{now} "
            + "WHERE id=#{id} AND user_id=#{userId} AND deleted=0 AND status='CONFIRMED'")
    int checkInConfirmedByOwner(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("checkinTime") LocalDateTime checkinTime,
            @Param("now") LocalDateTime now);

    @Update("UPDATE booking SET status='NO_SHOW',updated_at=#{now} "
            + "WHERE id=#{id} AND deleted=0 AND status='CONFIRMED'")
    int markNoShowConfirmed(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM booking WHERE deleted=0 AND user_id=#{userId} AND status IN "
            + BookingMapper.ACTIVE_STATUSES)
    long countActiveByUser(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM blacklist WHERE user_id=#{userId} "
            + "AND start_date<=#{today} AND #{today}<=end_date")
    long countActiveBlacklist(@Param("userId") long userId, @Param("today") LocalDate today);

    @Select("SELECT COUNT(*) FROM booking_slot WHERE resource_id=#{resourceId} "
            + "AND slot_time>=#{start} AND slot_time<#{end}")
    long countOccupiedSlots(
            @Param("resourceId") long resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
