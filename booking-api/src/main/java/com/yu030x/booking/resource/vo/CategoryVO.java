package com.yu030x.booking.resource.vo;

import java.time.LocalDateTime;
import java.util.List;

public record CategoryVO(
        String id,
        String name,
        String parentId,
        Integer sortOrder,
        String icon,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CategoryVO> children) {}
