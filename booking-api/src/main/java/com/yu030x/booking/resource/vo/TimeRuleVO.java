package com.yu030x.booking.resource.vo;

import java.time.LocalDateTime;
import java.time.LocalTime;

public record TimeRuleVO(
        String id,
        String resourceId,
        Integer dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime createdAt) {}
