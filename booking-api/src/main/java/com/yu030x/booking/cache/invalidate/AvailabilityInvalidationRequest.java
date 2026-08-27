package com.yu030x.booking.cache.invalidate;

import com.yu030x.booking.cache.key.AvailabilityCacheKey;

/** Invalidation intent produced by an owning mutation; carried to the coordinator. */
public record AvailabilityInvalidationRequest(String key, String origin) {

    public AvailabilityInvalidationRequest {
        // Arbitrary Redis keys are unacceptable here: only exact availability
        // cache keys may enter the invalidation path, so secrets/foreign
        // namespaces can neither travel into diagnostics nor reach deletion.
        if (!AvailabilityCacheKey.isExact(key)) {
            throw new IllegalArgumentException(
                    "invalidation requires an exact availability cache key");
        }
        origin = origin == null || origin.isBlank() ? "unspecified" : origin;
    }
}
