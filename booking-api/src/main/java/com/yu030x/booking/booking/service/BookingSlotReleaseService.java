package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.mapper.BookingOccupancyMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSlotReleaseService {
    private final BookingOccupancyMapper slotMapper;

    public BookingSlotReleaseService(@Lazy BookingOccupancyMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Transactional
    public int releaseTerminalSlots(long bookingId) {
        return slotMapper.deleteByBookingId(bookingId);
    }
}
