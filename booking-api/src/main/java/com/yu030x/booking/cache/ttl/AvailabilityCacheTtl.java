package com.yu030x.booking.cache.ttl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic availability-cache expiry: {@code 300 + (uint32(SHA-256(key)[0..3]) mod 601)}
 * over the big-endian unsigned first four digest bytes, yielding an inclusive
 * 300..900 second window (5..15 minutes) that is stable per key.
 */
public final class AvailabilityCacheTtl {

    /** Inclusive lower bound in seconds. */
    public static final int MIN_SECONDS = 300;
    /** Inclusive upper bound in seconds ({@code 300 + 600}). */
    public static final int MAX_SECONDS = 900;
    /** Jitter window size ({@code mod 601}) so both bounds are reachable. */
    public static final long WINDOW_MOD = 601L;

    private AvailabilityCacheTtl() {
    }

    public static int ttlSeconds(String key) {
        if (key == null) {
            throw new IllegalArgumentException("cache key must not be null");
        }
        byte[] digest = sha256(key.getBytes(StandardCharsets.UTF_8));
        long unsignedBigEndian =
                ((digest[0] & 0xFFL) << 24)
                        | ((digest[1] & 0xFFL) << 16)
                        | ((digest[2] & 0xFFL) << 8)
                        | (digest[3] & 0xFFL);
        long jitter = unsignedBigEndian % WINDOW_MOD;
        return MIN_SECONDS + (int) jitter;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException mandatoryInJca) {
            throw new IllegalStateException("SHA-256 unavailable", mandatoryInJca);
        }
    }
}
