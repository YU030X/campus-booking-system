package com.yu030x.booking.auth.security;

import javax.crypto.SecretKey;

public record JwtSettings(SecretKey secretKey, long ttlSeconds) {
}
