package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.vo.BookingView;
import org.springframework.stereotype.Component;

@Component
class PendingBookingLifecycleActions implements BookingLifecycleActions {
    @Override
    public BookingView approve(long bookingId, long operatorId) {
        throw new UnsupportedOperationException("booking lifecycle actions arrive with T09");
    }

    @Override
    public BookingView reject(long bookingId, long operatorId, String comment) {
        throw new UnsupportedOperationException("booking lifecycle actions arrive with T09");
    }

    @Override
    public BookingView cancel(long bookingId, String reason) {
        throw new UnsupportedOperationException("booking lifecycle actions arrive with T10");
    }

    @Override
    public BookingView checkIn(long bookingId) {
        throw new UnsupportedOperationException("booking lifecycle actions arrive with T10");
    }

    @Override
    public BookingView markNoShow(long bookingId) {
        throw new UnsupportedOperationException("booking lifecycle actions arrive with T10");
    }
}
