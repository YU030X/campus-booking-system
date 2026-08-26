package com.yu030x.booking.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NoShowScanTaskTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0, 0);

    private BookingMapper bookingMapper;
    private NoShowItemProcessor processor;
    private NoShowScanTask task;

    @BeforeEach
    void setUp() {
        bookingMapper = mock(BookingMapper.class);
        processor = mock(NoShowItemProcessor.class);
        task = new NoShowScanTask(bookingMapper, processor,
                Clock.fixed(NOW.atZone(SHANGHAI).toInstant(), SHANGHAI), SHANGHAI);
    }

    private BookingEntity candidate(long id, long userId) {
        BookingEntity entity = new BookingEntity();
        entity.setId(id);
        entity.setUserId(userId);
        return entity;
    }

    @Test
    @SuppressWarnings("unchecked")
    void scanSelectsOnlyConfirmedBookingsStrictlyOlderThanFifteenMinutes() {
        when(bookingMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        task.scanOnce();

        ArgumentCaptor<Wrapper<BookingEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(bookingMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BookingEntity> wrapper =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BookingEntity>) captor.getValue();
        String segment = wrapper.getSqlSegment();
        assertThat(segment).contains("status");
        assertThat(segment).contains("start_time <");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("CONFIRMED", NOW.minusMinutes(15));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneFailingCandidateDoesNotBlockRemainingCandidates() {
        when(bookingMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(candidate(1L, 5L), candidate(2L, 6L), candidate(3L, 7L)));
        doThrow(new IllegalStateException("boom")).when(processor).process(1L, 5L);
        when(processor.process(2L, 6L)).thenReturn(true);
        when(processor.process(3L, 7L)).thenReturn(false);

        NoShowScanTask.ScanSummary summary = task.scanOnce();

        assertThat(summary.candidates()).isEqualTo(3);
        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(processor).process(3L, 7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyCandidateListProducesEmptySummaryWithoutProcessorCalls() {
        when(bookingMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        NoShowScanTask.ScanSummary summary = task.scanOnce();

        assertThat(summary.candidates()).isZero();
        assertThat(summary.processed()).isZero();
        assertThat(summary.skipped()).isZero();
        assertThat(summary.failed()).isZero();
        verify(processor, never()).process(anyLong(), anyLong());
    }
}
