package com.yu030x.booking.availability;

import java.time.LocalDate;
import java.util.List;

public record AvailabilityVO(
        String resourceId,
        LocalDate date,
        int slotMinutes,
        List<SlotVO> slots) {
    public record SlotVO(String startTime, String endTime, boolean available) {}
}
