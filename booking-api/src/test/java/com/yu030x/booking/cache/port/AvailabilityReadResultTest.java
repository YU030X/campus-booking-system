package com.yu030x.booking.cache.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvailabilityReadResultTest {

    @Test
    void hitRequiresNonBlankPayload() {
        assertTrue(AvailabilityReadResult.hit("{\"slots\":[]}").isHit());
        assertEquals("{\"slots\":[]}", AvailabilityReadResult.hit("{\"slots\":[]}").value());
        assertThrows(IllegalArgumentException.class, () -> AvailabilityReadResult.hit(null),
                "null payload must not masquerade as HIT");
        assertThrows(IllegalArgumentException.class, () -> AvailabilityReadResult.hit("   "),
                "blank payload must not masquerade as HIT");
    }

    @Test
    void missAndFailureCarryNoValueEver() {
        assertEquals(null, AvailabilityReadResult.miss().value());
        assertEquals(null, AvailabilityReadResult.failure().value());
        assertFalse(AvailabilityReadResult.miss().isHit());
        assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityReadResult(AvailabilityReadResult.Status.MISS, "stray"),
                "MISS must reject stray payloads");
        assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityReadResult(AvailabilityReadResult.Status.FAILURE, "stray"));
    }

    @Test
    void statusItselfCanNeverBeNull() {
        assertThrows(IllegalArgumentException.class, () -> new AvailabilityReadResult(null, null));
    }
}
