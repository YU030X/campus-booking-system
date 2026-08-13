package com.yu030x.booking.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer custom() {
        return builder -> builder
                .timeZone("Asia/Shanghai")
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializers(new JsonSerializer<Long>() {
                    @Override
                    public Class<Long> handledType() { return Long.class; }
                    @Override
                    public void serialize(Long value, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        generator.writeString(value.toString());
                    }
                }, new JsonSerializer<LocalDateTime>() {
                    @Override
                    public Class<LocalDateTime> handledType() { return LocalDateTime.class; }
                    @Override
                    public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        generator.writeString(value.format(DATE_TIME));
                    }
                }, new JsonSerializer<LocalDate>() {
                    @Override
                    public Class<LocalDate> handledType() { return LocalDate.class; }
                    @Override
                    public void serialize(LocalDate value, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        generator.writeString(value.format(DATE));
                    }
                }, new JsonSerializer<LocalTime>() {
                    @Override
                    public Class<LocalTime> handledType() { return LocalTime.class; }
                    @Override
                    public void serialize(LocalTime value, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        generator.writeString(value.format(TIME));
                    }
                });
    }
}
