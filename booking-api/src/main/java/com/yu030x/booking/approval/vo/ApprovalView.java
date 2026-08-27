package com.yu030x.booking.approval.vo;

import com.yu030x.booking.approval.entity.ApprovalRecordEntity;
import java.time.LocalDateTime;

public record ApprovalView(
        String id,
        String bookingId,
        String approverId,
        String action,
        String comment,
        LocalDateTime createdAt) {

    public static ApprovalView from(ApprovalRecordEntity entity) {
        return new ApprovalView(
                String.valueOf(entity.getId()),
                String.valueOf(entity.getBookingId()),
                String.valueOf(entity.getApproverId()),
                entity.getAction(),
                entity.getComment(),
                entity.getCreatedAt());
    }
}
