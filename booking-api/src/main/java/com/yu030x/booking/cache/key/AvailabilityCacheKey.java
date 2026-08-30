package com.yu030x.booking.cache.key;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Strict construction and validation of the frozen availability cache key:
 * {@code resource:available-slots:{resourceId}:{yyyy-MM-dd}}. The id must be a
 * positive decimal Long rendered canonically (no sign, no leading zeros); the
 * day part must be a real calendar date whose canonical ISO string equals the
 * given input verbatim (raw text is never normalized or trimmed).
 */
public final class AvailabilityCacheKey {

    public static final String PREFIX = "resource:available-slots:";
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");

    private AvailabilityCacheKey() {
    }

    public static String of(Long resourceId, LocalDate date) {
        if (resourceId == null || resourceId <= 0L) {
            throw new IllegalArgumentException("resourceId must be a positive (>0) Long");
        }
        if (date == null) {
            throw new IllegalArgumentException("date must be provided");
        }
        return PREFIX + resourceId + ":" + date;
    }

    /** Convenience overload validating the raw day text against the strict shape. */
    public static String of(Long resourceId, String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            throw new IllegalArgumentException("date must be provided");
        }
        try {
            LocalDate parsed = LocalDate.parse(isoDate);
            if (!parsed.toString().equals(isoDate)) {
                throw new IllegalArgumentException("date must use canonical yyyy-MM-dd: " + isoDate);
            }
            return of(resourceId, parsed);
        } catch (DateTimeParseException rejectedCalendarShape) {
            throw new IllegalArgumentException("invalid calendar date: " + isoDate,
                    rejectedCalendarShape);
        }
    }

    /**
     * Exact-shape validation used by the adapter guard: frozen prefix, a
     * positive canonical decimal id (rejects signs, zeros and leading zeros)
     * and a canonical calendar date segment. Unknown shapes are never sent to
     * Redis.
     */
    public static boolean isExact(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return false;
        }
        String rest = key.substring(PREFIX.length());
        int separator = rest.lastIndexOf(':');
        if (separator <= 0 || separator == rest.length() - 1) {
            return false;
        }
        String idSegment = rest.substring(0, separator);
        String daySegment = rest.substring(separator + 1);
        if (!POSITIVE_DECIMAL.matcher(idSegment).matches()) {
            return false;
        }
        try {
            // Regex shape alone cannot bound the magnitude: digits past Long.MAX_VALUE
            // must also be rejected so downstream Long-based handling stays lossless.
            long resourceIdValue = Long.parseLong(idSegment);
            return resourceIdValue > 0L && LocalDate.parse(daySegment).toString().equals(daySegment);
        } catch (NumberFormatException | DateTimeParseException invalidKeySegment) {
            return false;
        }
    }
}
