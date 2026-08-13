package com.yu030x.booking.auth;

import com.yu030x.booking.user.UserView;

public record LoginResponse(String token, String tokenType, long expiresIn, UserView user) {
}
