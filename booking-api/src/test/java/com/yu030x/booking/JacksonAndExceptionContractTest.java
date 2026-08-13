package com.yu030x.booking.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.common.config.JacksonConfig;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonAndExceptionContractTest {
    @Test
    void serializesLongAndJavaTimeWithApiFormats() throws Exception {
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder()
                .modules(new JavaTimeModule());
        new JacksonConfig().custom().customize(builder);
        ObjectMapper mapper = builder.build();

        var json = mapper.readTree(mapper.writeValueAsString(Map.of("id", 42L,
                        "dateTime", LocalDateTime.of(2026, 8, 13, 9, 5, 7),
                        "date", LocalDate.of(2026, 8, 13), "time", LocalTime.of(9, 5, 7))));
        assertEquals("42", json.get("id").textValue());
        assertEquals("2026-08-13 09:05:07", json.get("dateTime").textValue());
        assertEquals("2026-08-13", json.get("date").textValue());
        assertEquals("09:05:07", json.get("time").textValue());
    }

    @Test
    void mapsRequestAndBusinessExceptionsToTheirHttpContracts() {
        var handler = new GlobalExceptionHandler();
        var malformed = handler.invalidParameter(new HttpMessageNotReadableException("bad", (Throwable) null));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals(ErrorCode.INVALID_PARAMETER.code, malformed.getBody().code());
        var constraint = handler.invalidParameter(new ConstraintViolationException("bad", java.util.Set.of()));
        assertEquals(ErrorCode.INVALID_PARAMETER.code, constraint.getBody().code());
        var business = handler.biz(new BizException(ErrorCode.NOT_FOUND, "missing"));
        assertEquals(HttpStatus.NOT_FOUND, business.getStatusCode());
        assertEquals(ErrorCode.NOT_FOUND.code, business.getBody().code());
    }
}
