package com.yu030x.booking.auth.security;

import com.yu030x.booking.user.UserRole;

public record BookingPrincipal(long id, String username, UserRole role) {
}
