package com.yu030x.booking.booking.service;

import java.time.LocalDateTime;

/**
 * T09/T10 handoff port: named conditional booking actions with explicit
 * outcomes. Every method runs with REQUIRED propagation so it joins the
 * caller's transaction; winning terminal transitions (reject/cancel/no-show)
 * release all slots inside that same transaction. cancel/checkIn take the
 * caller's single-Clock actionTime; a cancel at or after start_time is an
 * illegal transition. No arbitrary target-state update is exposed and callers
 * never touch booking persistence directly.
 */
public interface BookingActions {
    BookingActionOutcome approve(long bookingId);

    BookingActionOutcome reject(long bookingId);

    BookingActionOutcome cancel(long bookingId, long ownerUserId, LocalDateTime actionTime, String cancelReason);

    BookingActionOutcome checkIn(long bookingId, long ownerUserId, LocalDateTime checkinTime);

    BookingActionOutcome markNoShow(long bookingId);
}
