package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.DefaultBookingAdminReads;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultBookingAdminReadsTest {
    private BookingMapper bookingMapper;
    private DefaultBookingAdminReads reads;

    @BeforeEach
    void setUp() {
        bookingMapper = mock(BookingMapper.class);
        reads = new DefaultBookingAdminReads(bookingMapper);
    }

    private BookingEntity entity(long id, String bookingNo) {
        BookingEntity entity = new BookingEntity();
        entity.setId(id);
        entity.setBookingNo(bookingNo);
        entity.setStatus(com.yu030x.booking.common.api.BookingStatus.PENDING_APPROVAL);
        entity.setDeleted(0);
        return entity;
    }

    @Test
    void mapsPendingPageToViewsWithCanonicalEnvelope() {
        Page<BookingEntity> source = new Page<>(2, 20, 2);
        source.setRecords(List.of(entity(7L, "BK1"), entity(9L, "BK2")));
        when(bookingMapper.selectPendingApprovalPage(any())).thenReturn(source);

        PageResult<com.yu030x.booking.booking.vo.BookingView> page = reads.pagePendingApprovals(2, 20);

        verify(bookingMapper).selectPendingApprovalPage(any());
        assertEquals(2, page.pageNumber());
        assertEquals(20, page.pageSize());
        assertEquals(2, page.total());
        assertEquals("7", page.records().get(0).id());
        assertEquals("9", page.records().get(1).id());
    }

    @Test
    void rejectsOutOfBoundsPagesBeforeQuerying() {
        assertThrows(BizException.class, () -> reads.pagePendingApprovals(0, 10));
        assertThrows(BizException.class, () -> reads.pagePendingApprovals(-1, 10));
        assertThrows(BizException.class, () -> reads.pagePendingApprovals(1, 0));
        assertThrows(BizException.class, () -> reads.pagePendingApprovals(1, 101));

        BizException exception = assertThrows(BizException.class,
                () -> reads.pagePendingApprovals(1, 101));
        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode);
    }
}
