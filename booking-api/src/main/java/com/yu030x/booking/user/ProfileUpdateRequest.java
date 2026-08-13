package com.yu030x.booking.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.yu030x.booking.auth.TextNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ProfileUpdateRequest(
        @NotBlank @Size(max = 50) String realName,
        @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
        @Email @Size(max = 100) String email,
        @Size(max = 255) String avatar) {
    public ProfileUpdateRequest {
        realName = TextNormalizer.required(realName);
        phone = TextNormalizer.optional(phone);
        email = TextNormalizer.optional(email);
        avatar = TextNormalizer.optional(avatar);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
