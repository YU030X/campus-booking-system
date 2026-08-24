package com.yu030x.booking.availability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime NOW = TODAY.atTime(9, 15);

    @Test
    void halfHourBoundariesAndHalfOpen() {
        List<AvailabilityCalculator.Slot> slots = AvailabilityCalculator.calculate(
                TODAY, TODAY, NOW, 2, List.of(interval("09:00", "10:00")));

        assertEquals(
                List.of(LocalTime.of(9, 30)),
                slots.stream().map(AvailabilityCalculator.Slot::start).toList());
    }

    @Test
    void quarterBoundariesRejected() {
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCalculator.calculate(
                TODAY, TODAY, NOW, 1, List.of(interval("09:15", "10:00"))));
    }

    @Test
    void multipleGapAdjacentAndDedupe() {
        List<AvailabilityCalculator.Slot> slots = AvailabilityCalculator.calculate(
                TODAY.plusDays(1),
                TODAY,
                NOW,
                2,
                List.of(
                        interval("09:00", "10:00"),
                        interval("10:00", "11:00"),
                        interval("13:00", "14:00"),
                        interval("09:00", "10:00")));

        assertEquals(6, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start());
        assertEquals(LocalTime.of(13, 0), slots.get(4).start());
    }

    @Test
    void sameDayStrictlyAfterNow() {
        List<AvailabilityCalculator.Slot> slots = AvailabilityCalculator.calculate(
                TODAY, TODAY, TODAY.atTime(9, 30), 1, List.of(interval("09:00", "11:00")));

        assertEquals(
                List.of(LocalTime.of(10, 0), LocalTime.of(10, 30)),
                slots.stream().map(AvailabilityCalculator.Slot::start).toList());
    }

    @Test
    void futureTimeNotFiltered() {
        assertEquals(4, AvailabilityCalculator.calculate(
                TODAY.plusDays(1),
                TODAY,
                TODAY.atTime(23, 0),
                1,
                List.of(interval("09:00", "11:00"))).size());
    }

    @Test
    void advanceInclusiveAndOutside() {
        assertDoesNotThrow(() -> AvailabilityCalculator.calculate(
                TODAY.plusDays(2), TODAY, NOW, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCalculator.calculate(
                TODAY.plusDays(3), TODAY, NOW, 2, List.of()));
    }

    @Test
    void nullNegativeCrossMidnightRejected() {
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCalculator.calculate(
                TODAY, TODAY, NOW, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCalculator.calculate(
                TODAY, TODAY, NOW, 1, List.of(interval("23:30", "00:00"))));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCalculator.calculate(
                TODAY, TODAY, NOW, 1, null));
    }

    private AvailabilityCalculator.Interval interval(String start, String end) {
        return new AvailabilityCalculator.Interval(LocalTime.parse(start), LocalTime.parse(end));
    }
}
