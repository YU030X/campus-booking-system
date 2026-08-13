package com.yu030x.booking.resource.dto;

public record ResourceRequest(
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
        Integer status) {}
