package com.yu030x.booking.resource.vo;

import java.time.LocalDateTime;

public record ResourceVO(
        String id,
        String categoryId,
        String name,
        String location,
        Integer capacity,
        String description,
        String images,
        Boolean needApproval,
        Integer maxAdvanceDays,
        Integer minDurationMinutes,
        Integer maxDurationMinutes,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
