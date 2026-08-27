package com.yu030x.booking.cache.port;

/**
 * Owner-consumable availability Cache Aside port. Redis is never a source of
 * correctness: reads degrade to MISS/FAILURE, writes and deletions are
 * best-effort booleans, and every method must swallow infrastructure faults
 * internally rather than throwing into the availability flow.
 *
 * <p>Invalid keys (not passing {@code AvailabilityCacheKey.isExact}) are also
 * contained here rather than by the owner: reads answer FAILURE (equivalent to
 * an outage upstream — fall back to the database calculation); writes and
 * deletions answer {@code false}. Nothing is thrown for invalid keys either.</p>
 */
public interface AvailabilityCachePort {

    /** Returns HIT/MISS/FAILURE; never throws. FAILURE behaves like MISS upstream. */
    AvailabilityReadResult read(String key);

    /** Best-effort store with the deterministic TTL; false when disabled or failed. */
    boolean write(String key, String value);

    /** Best-effort post-commit-style removal; false when disabled or failed. */
    boolean invalidate(String key);
}
