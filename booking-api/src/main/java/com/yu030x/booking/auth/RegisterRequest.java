package com.yu030x.booking.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.yu030x.booking.auth.validation.Utf8ByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$") String username,
        @NotNull @Utf8ByteLength(min = 8, max = 72) String password,
        @NotBlank @Size(max = 50) String realName,
        @Size(max = 30) String studentNo,
        @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
        @Email @Size(max = 100) String email,
        @Size(max = 255) String avatar) {
    public RegisterRequest {
        username = TextNormalizer.required(username);
        realName = TextNormalizer.required(realName);
        studentNo = TextNormalizer.optional(studentNo);
        phone = TextNormalizer.optional(phone);
        email = TextNormalizer.optional(email);
        avatar = TextNormalizer.optional(avatar);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
