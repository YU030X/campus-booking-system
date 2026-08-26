package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.vo.BookingView;
import org.springframework.stereotype.Component;

/**
 * T09/T10 handoff seam: later lifecycle changes must implement these explicit
 * domain actions instead of issuing arbitrary status updates. T07 intentionally
 * ships only this boundary plus the terminal slot-release operation.
 */
public interface BookingLifecycleActions {
    BookingView approve(long bookingId, long operatorId);

    BookingView reject(long bookingId, long operatorId, String comment);

    BookingView cancel(long bookingId, String reason);

    BookingView checkIn(long bookingId);

    BookingView markNoShow(long bookingId);
}
