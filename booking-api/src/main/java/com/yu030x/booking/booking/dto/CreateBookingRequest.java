package com.yu030x.booking.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

public record CreateBookingRequest(
        @NotBlank @Pattern(regexp = "[1-9]\\d*") String resourceId,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
        String purpose,
        @NotNull @Min(1) Integer attendeeCount) {

    public CreateBookingRequest {
        if (purpose != null) {
            purpose = purpose.trim();
            if (purpose.isEmpty()) {
                purpose = null;
            } else if (purpose.codePointCount(0, purpose.length()) > 500) {
                throw new IllegalArgumentException("purpose must not exceed 500 Unicode code points");
            }
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
