package com.yu030x.booking.task;

import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.notification.event.NotificationRequestedEvent;
import com.yu030x.booking.violation.port.ViolationPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction owner for one no-show candidate. REQUIRES_NEW isolates each item:
 * any failure rolls back only this item's booking transition, violation insert,
 * and credit deduction. The T07 markNoShow action releases all slots inside the
 * same transaction on a WINNER result, so no separate slot-release call is made.
 */
@Component
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NoShowItemProcessor {
    private final BookingActions bookingActions;
    private final ViolationPort violationPort;
    private final ApplicationEventPublisher events;

    public NoShowItemProcessor(BookingActions bookingActions, ViolationPort violationPort) {
        this(bookingActions, violationPort, null);
    }

    @Autowired
    public NoShowItemProcessor(BookingActions bookingActions, ViolationPort violationPort,
            ApplicationEventPublisher events) {
        this.bookingActions = bookingActions;
        this.violationPort = violationPort;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(long bookingId, long userId) {
        BookingActionOutcome outcome = bookingActions.markNoShow(bookingId);
        if (outcome.result() != BookingActionOutcome.Result.WINNER) {
            return false;
        }
        violationPort.recordNoShow(bookingId, userId);
        if (events != null) {
            events.publishEvent(new NotificationRequestedEvent(
                    userId, "违约提醒", "您未按时签到，已记录违约", "VIOLATION", bookingId));
        }
        return true;
    }
}
