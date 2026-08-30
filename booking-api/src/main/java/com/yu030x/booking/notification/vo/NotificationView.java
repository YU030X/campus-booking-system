package com.yu030x.booking.notification.vo;

import com.yu030x.booking.notification.entity.NotificationEntity;
import java.time.LocalDateTime;

/**
 * Strict outbound fields only; no password and no raw PII. {@code Long} ids
 * serialize as strings and {@code createdAt} as canonical
 * {@code yyyy-MM-dd HH:mm:ss} via the global Jackson configuration.
 */
public record NotificationView(
        Long id,
        Long userId,
        String title,
        String content,
        String type,
        Long bizId,
        Integer isRead,
        LocalDateTime createdAt) {

    public static NotificationView from(NotificationEntity entity) {
        return new NotificationView(entity.getId(), entity.getUserId(), entity.getTitle(),
                entity.getContent(), entity.getType(), entity.getBizId(), entity.getIsRead(),
                entity.getCreatedAt());
    }
}
