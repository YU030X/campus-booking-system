package com.yu030x.booking.user;

public enum UserStatus {
    DISABLED(0),
    ENABLED(1);

    private final int value;

    UserStatus(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean matches(Integer candidate) {
        return candidate != null && value == candidate;
    }

    public static boolean isValid(Integer candidate) {
        return DISABLED.matches(candidate) || ENABLED.matches(candidate);
    }
}
