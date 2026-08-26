package com.yu030x.booking.approval.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record ApproveRequest(String comment) {

    public ApproveRequest {
        if (comment != null) {
            comment = comment.trim();
            if (comment.isEmpty()) {
                comment = null;
            } else if (comment.codePointCount(0, comment.length()) > 500) {
                throw new IllegalArgumentException("comment must not exceed 500 Unicode code points");
            }
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
