package com.yu030x.booking.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.notification.event.NotificationRequestedEvent;
import com.yu030x.booking.violation.port.ViolationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class NoShowItemProcessorTest {
    private BookingActions bookingActions;
    private ViolationPort violationPort;
    private ApplicationEventPublisher events;
    private NoShowItemProcessor processor;

    @BeforeEach
    void setUp() {
        bookingActions = mock(BookingActions.class);
        violationPort = mock(ViolationPort.class);
        events = mock(ApplicationEventPublisher.class);
        processor = new NoShowItemProcessor(bookingActions, violationPort, events);
    }

    @Test
    void winnerRecordsNoShowViolationAndDeduction() {
        when(bookingActions.markNoShow(9L))
                .thenReturn(BookingActionOutcome.winner(null));

        assertThat(processor.process(9L, 5L)).isTrue();
        verify(violationPort).recordNoShow(9L, 5L);
        verify(events).publishEvent(new NotificationRequestedEvent(
                5L, "违约提醒", "您未按时签到，已记录违约", "VIOLATION", 9L));
    }

    @Test
    void alreadyCompletedOrRacedOutcomesNeverRecordOrDeduce() {
        for (BookingActionOutcome outcome : new BookingActionOutcome[]{
                BookingActionOutcome.alreadyCompleted(null),
                BookingActionOutcome.illegalTransition(null),
                BookingActionOutcome.notFound()}) {
            when(bookingActions.markNoShow(9L)).thenReturn(outcome);

            assertThat(processor.process(9L, 5L)).isFalse();
        }
        verify(violationPort, never()).recordNoShow(anyLong(), anyLong());
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
