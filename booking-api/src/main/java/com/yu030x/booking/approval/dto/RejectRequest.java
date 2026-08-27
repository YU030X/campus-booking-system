package com.yu030x.booking.approval.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record RejectRequest(String comment) {

    public RejectRequest {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("comment is required");
        }
        comment = comment.trim();
        if (comment.codePointCount(0, comment.length()) > 500) {
            throw new IllegalArgumentException("comment must not exceed 500 Unicode code points");
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
