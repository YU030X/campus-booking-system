package com.yu030x.booking.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.violation.port.ViolationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoShowItemProcessorTest {
    private BookingActions bookingActions;
    private ViolationPort violationPort;
    private NoShowItemProcessor processor;

    @BeforeEach
    void setUp() {
        bookingActions = mock(BookingActions.class);
        violationPort = mock(ViolationPort.class);
        processor = new NoShowItemProcessor(bookingActions, violationPort);
    }

    @Test
    void winnerRecordsNoShowViolationAndDeduction() {
        when(bookingActions.markNoShow(9L))
                .thenReturn(BookingActionOutcome.winner(null));

        assertThat(processor.process(9L, 5L)).isTrue();
        verify(violationPort).recordNoShow(9L, 5L);
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
    }
}
