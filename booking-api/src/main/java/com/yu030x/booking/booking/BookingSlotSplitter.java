package com.yu030x.booking.booking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class BookingSlotSplitter {
    private static final int SLOT_MINUTES = 30;

    private BookingSlotSplitter() {}

    public static List<LocalDateTime> split(LocalDateTime start, LocalDateTime end) {
        validate(start, end);

        List<LocalDateTime> slots = new ArrayList<>();
        for (LocalDateTime slotStart = start; slotStart.isBefore(end);
                slotStart = slotStart.plusMinutes(SLOT_MINUTES)) {
            slots.add(slotStart);
        }
        return slots;
    }

    private static void validate(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null
                || !start.toLocalDate().equals(end.toLocalDate())
                || !start.isBefore(end)
                || !validBoundary(start)
                || !validBoundary(end)) {
            throw new IllegalArgumentException("interval");
        }
    }

    private static boolean validBoundary(LocalDateTime value) {
        return value.getSecond() == 0
                && value.getNano() == 0
                && (value.getMinute() == 0 || value.getMinute() == SLOT_MINUTES);
    }
}
