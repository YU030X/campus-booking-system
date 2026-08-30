package com.yu030x.booking.cache.port;

/**
 * Outcome of an availability cache read. A FAILURE outcome is equivalent to a
 * miss for the caller: the availability calculation falls back to its database
 * computation and this never influences T07's booking lock decisions or any
 * correctness property.
 *
 * <p>Invariant enforced by the compact constructor: a HIT always carries a
 * non-blank value; MISS/FAILURE always carry {@code value == null}; the status
 * itself can never be null.</p>
 */
public record AvailabilityReadResult(Status status, String value) {

    public enum Status {
        HIT, MISS, FAILURE
    }

    public AvailabilityReadResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        switch (status) {
            case HIT -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("HIT requires a non-blank cached payload");
                }
            }
            case MISS, FAILURE -> {
                if (value != null) {
                    throw new IllegalArgumentException(status + " must not carry a value");
                }
            }
            default -> throw new IllegalArgumentException("unknown status");
        }
    }

    public static AvailabilityReadResult hit(String value) {
        return new AvailabilityReadResult(Status.HIT, value);
    }

    public static AvailabilityReadResult miss() {
        return new AvailabilityReadResult(Status.MISS, null);
    }

    public static AvailabilityReadResult failure() {
        return new AvailabilityReadResult(Status.FAILURE, null);
    }

    public boolean isHit() {
        return status == Status.HIT;
    }
}
