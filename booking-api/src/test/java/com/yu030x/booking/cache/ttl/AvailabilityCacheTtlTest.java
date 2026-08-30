package com.yu030x.booking.cache.ttl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AvailabilityCacheTtlTest {

    /** Independent second implementation of the frozen algorithm used as oracle. */
    private static int oracleTtl(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
        int firstFourBigEndian = ByteBuffer.wrap(digest, 0, 4).getInt();
        long unsigned = firstFourBigEndian & 0xFFFFFFFFL;
        return 300 + (int) (unsigned % 601);
    }

    @Test
    void matchesIndependentOracleAcrossKnownAndVariedKeys() throws Exception {
        String[] knownKeys = {
                "resource:available-slots:1:2026-01-01",
                "resource:available-slots:42:2026-08-27",
                "resource:available-slots:999999999999:2024-02-29",
                "resource:available-slots:0x_not_even_a_key_but_stable"
        };
        for (String key : knownKeys) {
            assertEquals(oracleTtl(key), AvailabilityCacheTtl.ttlSeconds(key),
                    () -> "formula mismatch for " + key);
            // Determinism: repeated evaluation returns the identical value.
            assertEquals(AvailabilityCacheTtl.ttlSeconds(key), AvailabilityCacheTtl.ttlSeconds(key));
        }
        LocalDate cursor = LocalDate.of(2026, 1, 1);
        for (long id : new long[]{2L, 33L, Long.MAX_VALUE}) {
            for (int i = 0; i < 40; i++) {
                String key = "resource:available-slots:" + id + ":" + cursor.plusDays(i);
                assertEquals(oracleTtl(key), AvailabilityCacheTtl.ttlSeconds(key));
            }
        }
    }

    @Test
    void everyResultStaysInsideInclusive300To900Bounds() {
        int minSeen = Integer.MAX_VALUE;
        int maxSeen = Integer.MIN_VALUE;
        for (long id = 1; id <= 400; id++) {
            int ttl = AvailabilityCacheTtl.ttlSeconds("resource:available-slots:"
                    + id + ":" + LocalDate.of(2026, 8, 27).plusDays(id - 1));
            assertTrue(ttl >= AvailabilityCacheTtl.MIN_SECONDS && ttl <= AvailabilityCacheTtl.MAX_SECONDS);
            minSeen = Math.min(minSeen, ttl);
            maxSeen = Math.max(maxSeen, ttl);
        }
        assertTrue(minSeen <= maxSeen);
        assertTrue(minSeen < maxSeen, "jitter window should produce more than one distinct TTL");
    }

    @Test
    void nullKeyIsRejectedDeterministically() {
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheTtl.ttlSeconds(null));
    }
}
