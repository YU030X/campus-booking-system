package com.yu030x.booking.violation.vo;

import com.yu030x.booking.violation.entity.ViolationRecordEntity;
import java.time.LocalDateTime;

public record ViolationView(
        Long id,
        Long userId,
        Long bookingId,
        String violationType,
        Integer scoreChange,
        String remark,
        LocalDateTime createdAt) {

    public static ViolationView from(ViolationRecordEntity entity) {
        return new ViolationView(entity.getId(), entity.getUserId(), entity.getBookingId(),
                entity.getViolationType(), entity.getScoreChange(), entity.getRemark(),
                entity.getCreatedAt());
    }
}
