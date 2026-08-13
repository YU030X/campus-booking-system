package com.yu030x.booking.auth.security;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class BookingPrincipalAccessor {
    public BookingPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof BookingPrincipal principal)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "unauthenticated");
        }
        return principal;
    }
}
