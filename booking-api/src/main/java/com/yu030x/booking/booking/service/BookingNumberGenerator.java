package com.yu030x.booking.booking.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class BookingNumberGenerator {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int RANDOM_CHARS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingNumberGenerator() {}

    public static String generate(LocalDateTime now) {
        StringBuilder builder = new StringBuilder("BK").append(STAMP.format(now));
        for (int index = 0; index < RANDOM_CHARS; index++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
