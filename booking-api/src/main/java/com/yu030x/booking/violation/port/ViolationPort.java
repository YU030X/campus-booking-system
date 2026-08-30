package com.yu030x.booking.violation.port;

/**
 * REQUIRED cross-change contract (frozen by openspec/changes/add-checkin-no-show-violation):
 * implementations MUST join the caller's transaction without opening a new one,
 * MUST keep at most one row per (bookingId, violationType) via the frozen
 * violation_record.uk_booking_type uniqueness, MUST treat a duplicate key as an
 * already-processed outcome that never triggers a second credit deduction, and
 * MUST apply the frozen score changes below through the T02 UserCreditPort so
 * the committed credit is max(0, currentCredit + scoreChange).
 */
public interface ViolationPort {
    int NO_SHOW_SCORE_CHANGE = -10;
    int LATE_CANCEL_SCORE_CHANGE = -5;

    void recordNoShow(long bookingId, long userId);

    void recordLateCancel(long bookingId, long userId);
}
