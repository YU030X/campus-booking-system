package com.yu030x.booking.resource.service;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

final class ResourceInputSupport {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");
    private static final Pattern HALF_HOUR_TIME =
            Pattern.compile("(?:[01][0-9]|2[0-3]):(?:00|30):00");

    private ResourceInputSupport() {}

    static BizException invalid() {
        return new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
    }

    static long decimalId(String value, boolean allowZero) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw invalid();
        }
        try {
            long id = Long.parseLong(value);
            if (!allowZero && id == 0) {
                throw invalid();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    static String requiredTrimmed(String value, int max) {
        if (value == null) {
            throw invalid();
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || characters(normalized) > max) {
            throw invalid();
        }
        return normalized;
    }

    static String trimmedToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (characters(normalized) > max) {
            throw invalid();
        }
        return normalized;
    }

    static String description(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (characters(value) > 10_000) {
            throw invalid();
        }
        return value;
    }

    static int bounded(Integer value, int defaultValue, int min, int max) {
        int normalized = value == null ? defaultValue : value;
        if (normalized < min || normalized > max) {
            throw invalid();
        }
        return normalized;
    }

    static int positiveMultipleOfThirty(Integer value, int defaultValue) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0 || normalized % 30 != 0) {
            throw invalid();
        }
        return normalized;
    }

    static int queryInteger(String value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        if (!DECIMAL.matcher(value).matches()) {
            throw invalid();
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    static LocalTime halfHourTime(String value) {
        if (value == null || !HALF_HOUR_TIME.matcher(value).matches()) {
            throw invalid();
        }
        return LocalTime.parse(value);
    }

    static LocalDate date(String value) {
        if (value == null) {
            throw invalid();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static int characters(String value) {
        return value.codePointCount(0, value.length());
    }
}
