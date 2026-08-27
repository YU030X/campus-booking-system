package com.yu030x.booking.statistics.dto;

import java.util.List;

/**
 * ADMIN booking-status envelope: exactly {fromDate,toDate,records}; no user
 * ids, names, phones, purposes, or raw booking rows. Records always contain
 * the seven frozen statuses in canonical order, zero-filled when absent.
 */
public record BookingStatusResponse(String fromDate, String toDate,
                                    List<BookingStatusAggregate> records) {

    /** Frozen {status,count} pair; count is a non-negative integer. */
    public record BookingStatusAggregate(String status, Integer count) {
    }
}
