package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.config.JacksonConfig;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class BookingViewTest {
    private final ObjectMapper objectMapper = objectMapper();

    @Test
    void exposesExactlyTheFrozenFieldsAndStringIds() throws Exception {
        BookingEntity entity = new BookingEntity();
        entity.setId(9007199254740993L);
        entity.setBookingNo("BK-opaque-value");
        entity.setUserId(9007199254740992L);
        entity.setResourceId(42L);
        entity.setStartTime(LocalDateTime.of(2026, 8, 26, 14, 0));
        entity.setEndTime(LocalDateTime.of(2026, 8, 26, 14, 30));
        entity.setPurpose(null);
        entity.setAttendeeCount(2);
        entity.setStatus(BookingStatus.CONFIRMED);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 8, 25, 12, 1));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(BookingView.from(entity)));
        Set<String> fieldNames = StreamSupport.stream(
                        ((Iterable<String>) () -> json.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "id", "bookingNo", "userId", "resourceId", "startTime", "endTime", "purpose",
                "attendeeCount", "status", "checkinTime", "cancelTime", "cancelReason",
                "createdAt", "updatedAt"), fieldNames);
        assertEquals("9007199254740993", json.get("id").textValue());
        assertEquals("9007199254740992", json.get("userId").textValue());
        assertEquals("42", json.get("resourceId").textValue());
        assertEquals("2026-08-26 14:00:00", json.get("startTime").textValue());
        assertTrue(json.get("checkinTime").isNull());
        assertTrue(json.get("cancelReason").isNull());
    }

    private ObjectMapper objectMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().custom().customize(builder);
        return builder.build();
    }
}
