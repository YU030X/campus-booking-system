package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.booking.dto.CreateBookingRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CreateBookingRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void normalizesPurposeAndAcceptsValidShape() throws Exception {
        CreateBookingRequest request = objectMapper.readValue("""
                {"resourceId":"42","startTime":"2026-08-26 14:00:00",
                 "endTime":"2026-08-26 14:30:00","purpose":"  小组讨论  ","attendeeCount":2}
                """, CreateBookingRequest.class);

        assertEquals("小组讨论", request.purpose());
        assertEquals(LocalDateTime.of(2026, 8, 26, 14, 0), request.startTime());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void blankPurposeBecomesNull() {
        CreateBookingRequest request = new CreateBookingRequest(
                "1",
                LocalDateTime.of(2026, 8, 26, 14, 0),
                LocalDateTime.of(2026, 8, 26, 14, 30),
                "  ",
                1);

        assertNull(request.purpose());
    }

    @Test
    void rejectsMoreThanFiveHundredUnicodeCodePoints() {
        String accepted = "😀".repeat(500);
        assertEquals(500, new CreateBookingRequest(
                "1",
                LocalDateTime.of(2026, 8, 26, 14, 0),
                LocalDateTime.of(2026, 8, 26, 14, 30),
                accepted,
                1).purpose().codePointCount(0, accepted.length()));

        assertThrows(IllegalArgumentException.class, () -> new CreateBookingRequest(
                "1",
                LocalDateTime.of(2026, 8, 26, 14, 0),
                LocalDateTime.of(2026, 8, 26, 14, 30),
                "😀".repeat(501),
                1));
    }

    @Test
    void rejectsUnknownFieldsEvenWhenMapperGloballyIgnoresThem() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"resourceId":"42","startTime":"2026-08-26 14:00:00",
                 "endTime":"2026-08-26 14:30:00","attendeeCount":2,"unexpected":true}
                """, CreateBookingRequest.class));
    }

    @Test
    void beanValidationRejectsMalformedIdsAndAttendeeCounts() {
        CreateBookingRequest request = new CreateBookingRequest(
                "not-a-decimal",
                LocalDateTime.of(2026, 8, 26, 14, 0),
                LocalDateTime.of(2026, 8, 26, 14, 30),
                null,
                0);

        assertEquals(2, validator.validate(request).size());
    }
}
