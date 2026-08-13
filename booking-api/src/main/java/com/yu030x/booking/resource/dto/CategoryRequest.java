package com.yu030x.booking.resource.dto;

public record CategoryRequest(String name, String parentId, Integer sortOrder, String icon) {}
