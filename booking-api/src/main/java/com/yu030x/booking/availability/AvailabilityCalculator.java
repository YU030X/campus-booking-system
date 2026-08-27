package com.yu030x.booking.availability;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class AvailabilityCalculator {
    private static final int SLOT_MINUTES = 30;

    private AvailabilityCalculator() {}

    public record Interval(LocalTime start, LocalTime end) {}

    public record Slot(LocalTime start, LocalTime end, boolean available) {}

    public static List<Slot> calculate(
            LocalDate date,
            LocalDate today,
            LocalDateTime now,
            int maxAdvanceDays,
            List<Interval> intervals) {
        if (date == null || today == null || now == null || maxAdvanceDays < 0
                || date.isBefore(today) || date.isAfter(today.plusDays(maxAdvanceDays))) {
            throw new IllegalArgumentException("date");
        }
        if (intervals == null) {
            throw new IllegalArgumentException("interval");
        }

        TreeSet<LocalTime> starts = new TreeSet<>();
        for (Interval interval : intervals) {
            validateInterval(interval);
            for (LocalTime start = interval.start(); start.isBefore(interval.end());
                    start = start.plusMinutes(SLOT_MINUTES)) {
                if (start.plusMinutes(SLOT_MINUTES).isAfter(interval.end())) {
                    throw new IllegalArgumentException("interval");
                }
                starts.add(start);
            }
        }

        List<Slot> slots = new ArrayList<>();
        for (LocalTime start : starts) {
            if (date.equals(today) && !LocalDateTime.of(date, start).isAfter(now)) {
                continue;
            }
            slots.add(new Slot(start, start.plusMinutes(SLOT_MINUTES), true));
        }
        return slots;
    }

    private static void validateInterval(Interval interval) {
        if (interval == null || interval.start() == null || interval.end() == null
                || !validBoundary(interval.start()) || !validBoundary(interval.end())
                || !interval.start().isBefore(interval.end())
                || interval.end().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("interval");
        }
    }

    private static boolean validBoundary(LocalTime time) {
        return time.getSecond() == 0
                && time.getNano() == 0
                && (time.getMinute() == 0 || time.getMinute() == SLOT_MINUTES);
    }
}
