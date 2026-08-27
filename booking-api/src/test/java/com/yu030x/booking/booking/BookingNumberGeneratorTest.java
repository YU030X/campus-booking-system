package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yu030x.booking.booking.service.BookingNumberGenerator;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookingNumberGeneratorTest {
    @Test
    void numbersAreFixedLengthPrefixedAndUnique() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 34, 56);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 1000; index++) {
            String number = BookingNumberGenerator.generate(now);
            assertTrue(number.startsWith("BK"));
            assertEquals(26, number.length());
            seen.add(number);
        }
        assertEquals(1000, seen.size());
    }

    @Test
    void numbersDoNotEncodeSequentialIds() {
        String first = BookingNumberGenerator.generate(LocalDateTime.of(2026, 8, 26, 1, 0));
        String second = BookingNumberGenerator.generate(LocalDateTime.of(2026, 8, 26, 1, 0));
        assertNotEquals(first, second);
    }
}
