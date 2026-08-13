package com.yu030x.booking.resource.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ClosureVO(
        String id,
        String resourceId,
        LocalDate closureDate,
        String reason,
        LocalDateTime createdAt) {}
