package com.yu030x.booking.notification.controller;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.notification.service.NotificationService;
import com.yu030x.booking.notification.vo.NotificationView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true", matchIfMissing = false)
public class NotificationController {
    private final NotificationService notificationService;
    private final BookingPrincipalAccessor principalAccessor;

    public NotificationController(NotificationService notificationService,
            BookingPrincipalAccessor principalAccessor) {
        this.notificationService = notificationService;
        this.principalAccessor = principalAccessor;
    }

    @GetMapping
    Result<PageResult<NotificationView>> list(
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(notificationService.pageForCurrentUser(
                principalAccessor.current().id(), pageNumber, pageSize));
    }

    @PostMapping("/{id}/read")
    Result<Void> markRead(@PathVariable String id) {
        notificationService.markReadForCurrentUser(
                principalAccessor.current().id(), parseNotificationId(id));
        return Result.success(null);
    }

    /** Accepts only canonical positive decimal ids: 0, negatives, signs, and overflow are 40000 before any mapper call. */
    private long parseNotificationId(String id) {
        if (id == null || !id.matches("[0-9]+")) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid notification id");
        }
        try {
            long value = Long.parseLong(id);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Overflow beyond Long falls through to the shared rejection below.
        }
        throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid notification id");
    }
}
