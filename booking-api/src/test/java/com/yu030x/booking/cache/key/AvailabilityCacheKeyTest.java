package com.yu030x.booking.cache.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AvailabilityCacheKeyTest {

    @Test
    void buildsFrozenShapeForPlainResourceIdsAndIsoDates() {
        assertEquals("resource:available-slots:42:2026-08-27",
                AvailabilityCacheKey.of(42L, LocalDate.of(2026, 8, 27)));
        assertEquals("resource:available-slots:7:2024-02-29",
                AvailabilityCacheKey.of(7L, "2024-02-29"), "leap day is a valid calendar date");
        assertEquals(LocalDate.parse("2025-12-31"), LocalDate.of(2025, 12, 31));
    }

    @Test
    void rejectsZeroNullAndNegativeContractViolations() {
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(0L,
                LocalDate.of(2026, 1, 1)), "zero id must be rejected");
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(null,
                LocalDate.of(2026, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(-3L,
                LocalDate.of(2026, 1, 1)), "negative ids must be rejected: only id > 0 is legal");
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(Long.MIN_VALUE,
                LocalDate.of(2026, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(1L, (LocalDate) null));
    }

    @Test
    void exactShapeValidationGatesAdapterAccess() {
        assertTrue(AvailabilityCacheKey.isExact("resource:available-slots:42:2026-08-27"));
        assertTrue(AvailabilityCacheKey.isExact("resource:available-slots:"
                + Long.MAX_VALUE + ":2024-02-29"));

        assertFalse(AvailabilityCacheKey.isExact(null));
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots"), "prefix alone");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slotsx:42:2026-08-27"),
                "tampered prefix");
        assertFalse(AvailabilityCacheKey.isExact("booking:lock:42:2026-08-27"), "foreign namespace");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:0:2026-08-27"),
                "zero id");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:-7:2026-08-27"),
                "signed id");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:+7:2026-08-27"),
                "explicit plus");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:042:2026-08-27"),
                "leading zeros are not canonical");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:4 2:2026-08-27"),
                "space inside id");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:2026-08-27"),
                "missing id segment");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:42:"), "empty day");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:42:2026-2-27"),
                "non canonical month");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:42:2026-02-30"),
                "impossible day");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:42:2026/08/27"),
                "slash date");
        assertFalse(AvailabilityCacheKey.isExact("resource:available-slots:42: 2026-08-27"),
                "padding before day");
        assertFalse(AvailabilityCacheKey.isExact(" resource:available-slots:42:2026-08-27"),
                "padding before prefix");
        // Beyond-Long digits pass the decimal regex but must fail magnitude parsing.
        assertFalse(AvailabilityCacheKey.isExact(
                        "resource:available-slots:9223372036854775808:2026-08-27"),
                "Long.MAX_VALUE + 1");
        assertFalse(AvailabilityCacheKey.isExact(
                        "resource:available-slots:999999999999999999999999999999:2026-08-27"),
                "absurdly huge digit run");
        assertTrue(AvailabilityCacheKey.isExact(
                        "resource:available-slots:9223372036854775807:2026-08-27"),
                "exactly Long.MAX_VALUE stays legal");
    }

    @Test
    void stringDateOverloadRejectsNonCanonicalOrImpossibleDates() {
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(9L, "2026-2-27"),
                "non zero-padded month must be rejected");
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(9L, "2026-02-30"),
                "impossible calendar date must be rejected");
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(9L, "2026/08/27"));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(9L, "20260827"));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityCacheKey.of(9L, (String) null));
        // Canonical accepted forms round-trip unchanged:
        assertEquals("resource:available-slots:9:2026-08-27",
                AvailabilityCacheKey.of(9L, "2026-08-27"));
    }
}
