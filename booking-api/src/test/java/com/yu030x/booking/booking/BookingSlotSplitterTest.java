package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingSlotSplitterTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 15);

    @Test
    void halfHourIntervalProducesOneStart() {
        assertEquals(
                List.of(time("14:00")),
                BookingSlotSplitter.split(time("14:00"), time("14:30")));
    }

    @Test
    void twoHourIntervalProducesFourStarts() {
        assertEquals(
                List.of(time("14:00"), time("14:30"), time("15:00"), time("15:30")),
                BookingSlotSplitter.split(time("14:00"), time("16:00")));
    }

    @Test
    void endIsExcluded() {
        List<LocalDateTime> starts = BookingSlotSplitter.split(time("14:00"), time("15:00"));

        assertEquals(List.of(time("14:00"), time("14:30")), starts);
    }

    @Test
    void adjacentHalfOpenIntervalsDoNotOverlap() {
        List<LocalDateTime> first = BookingSlotSplitter.split(time("14:00"), time("15:00"));
        List<LocalDateTime> second = BookingSlotSplitter.split(time("15:00"), time("16:00"));

        assertEquals(List.of(time("14:00"), time("14:30")), first);
        assertEquals(List.of(time("15:00"), time("15:30")), second);
    }

    @Test
    void invalidBoundariesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:15"), time("15:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00:01"), time("15:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00:00.000000001"), time("15:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00"), time("15:15")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00"), time("15:00:01")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00"), time("15:00:00.000000001")));
    }

    @Test
    void emptyReversedCrossDayAndNullIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00"), time("14:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("15:00"), time("14:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("23:30"), DATE.plusDays(1).atStartOfDay()));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(null, time("14:00")));
        assertThrows(IllegalArgumentException.class,
                () -> BookingSlotSplitter.split(time("14:00"), null));
    }

    private static LocalDateTime time(String value) {
        return LocalDateTime.parse(DATE + "T" + value);
    }
}
