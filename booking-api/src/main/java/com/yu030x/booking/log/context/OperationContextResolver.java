package com.yu030x.booking.log.context;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves operator identity and client IP from public ports owned elsewhere.
 * Every lookup is best-effort: missing web context or unauthenticated principal
 * yields {@code null} instead of failing or altering the business call.
 */
public class OperationContextResolver {

    private final ObjectProvider<BookingPrincipalAccessor> principalAccessors;

    public OperationContextResolver(ObjectProvider<BookingPrincipalAccessor> principalAccessors) {
        this.principalAccessors = principalAccessors;
    }

    /** Returns the authenticated user id or null; never throws. */
    public Long currentUserId() {
        try {
            BookingPrincipalAccessor accessor = principalAccessors.getIfAvailable();
            if (accessor == null) {
                return null;
            }
            return accessor.current().id();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns the bounded client IP from the current request or null outside web threads. */
    public String currentIp() {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            String ip = (forwarded == null || forwarded.isBlank())
                    ? request.getRemoteAddr()
                    : forwarded.split(",")[0].trim();
            if (ip == null || ip.isBlank()) {
                return null;
            }
            return ip.length() > 50 ? ip.substring(0, 50) : ip;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
