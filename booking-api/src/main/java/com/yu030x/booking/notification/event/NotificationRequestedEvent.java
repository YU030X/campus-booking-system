package com.yu030x.booking.notification.event;

/**
 * Post-commit delivery request raised by any future producer. Producer wiring
 * is owned by the corresponding owner slices and intentionally not connected
 * this round; only the consumer side lives here.
 *
 * <p>The event is a plain carrier: {@code NotificationDelivery} enforces the
 * unified contract at delivery time (code-point limits 100/1000/30, positive
 * userId/bizId, secret screen), so a rejected request causes zero writes and
 * secrets never reach persistence. Keep payloads free of credentials anyway.</p>
 *
 * @param userId   recipient (must reference a living {@code user} row)
 * @param title    at most 100 code points
 * @param content  at most 1000 code points
 * @param type     at most 30 code points (e.g. BOOKING_APPROVED / REMIND / VIOLATION)
 * @param bizId    optional positive business id for dedup and navigation, may be null
 */
public record NotificationRequestedEvent(
        long userId,
        String title,
        String content,
        String type,
        Long bizId) {
}
