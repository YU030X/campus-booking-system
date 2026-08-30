package com.yu030x.booking.approval.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record CancelRequest(String cancelReason) {

    public CancelRequest {
        if (cancelReason != null) {
            cancelReason = cancelReason.trim();
            if (cancelReason.isEmpty()) {
                cancelReason = null;
            } else if (cancelReason.codePointCount(0, cancelReason.length()) > 200) {
                throw new IllegalArgumentException("cancelReason must not exceed 200 Unicode code points");
            }
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
