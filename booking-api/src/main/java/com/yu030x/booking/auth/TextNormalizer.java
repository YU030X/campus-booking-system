package com.yu030x.booking.auth;

public final class TextNormalizer {
    private TextNormalizer() {
    }

    public static String required(String value) {
        return value == null ? null : value.trim();
    }

    public static String optional(String value) {
        String normalized = required(value);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }
}
