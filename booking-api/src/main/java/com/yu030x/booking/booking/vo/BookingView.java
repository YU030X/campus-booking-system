package com.yu030x.booking.booking.vo;

import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.common.api.BookingStatus;
import java.time.LocalDateTime;

public record BookingView(
        String id,
        String bookingNo,
        String userId,
        String resourceId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String purpose,
        Integer attendeeCount,
        BookingStatus status,
        LocalDateTime checkinTime,
        LocalDateTime cancelTime,
        String cancelReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BookingView from(BookingEntity entity) {
        return new BookingView(
                String.valueOf(entity.getId()),
                entity.getBookingNo(),
                String.valueOf(entity.getUserId()),
                String.valueOf(entity.getResourceId()),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getPurpose(),
                entity.getAttendeeCount(),
                entity.getStatus(),
                entity.getCheckinTime(),
                entity.getCancelTime(),
                entity.getCancelReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
