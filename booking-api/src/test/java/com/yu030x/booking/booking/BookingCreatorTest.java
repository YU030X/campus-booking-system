package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.mapper.BookingOccupancyMapper;
import com.yu030x.booking.booking.service.BookingCreationGuard;
import com.yu030x.booking.booking.service.BookingCreator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.entity.ResourceEntity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class BookingCreatorTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDate.of(2026, 8, 26).atTime(10, 0);

    private BookingMapper bookingMapper;
    private BookingOccupancyMapper slotMapper;
    private BookingCreationGuard guard;
    private BookingCreator creator;
    private ResourceEntity resource;
    private CreateBookingRequest request;

    @BeforeEach
    void setUp() {
        bookingMapper = mock(BookingMapper.class);
        slotMapper = mock(BookingOccupancyMapper.class);
        guard = mock(BookingCreationGuard.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        creator = new BookingCreator(bookingMapper, slotMapper, guard, clock);

        resource = new ResourceEntity();
        resource.setId(7L);
        resource.setStatus(1);
        resource.setNeedApproval(false);
        when(guard.checkAll(anyLong(), anyLong(), anyInt(), any(), any())).thenReturn(resource);

        request = new CreateBookingRequest(
                "7", NOW.toLocalDate().atTime(14, 0), NOW.toLocalDate().atTime(15, 0), null, 2);
    }

    @Test
    void insertsOneBookingAndAllSlotsAtomically() {
        when(bookingMapper.insert(any(BookingEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, BookingEntity.class).setId(99L);
            return 1;
        });
        when(slotMapper.batchInsert(anyLong(), anyLong(), anyList())).thenReturn(2);

        var view = creator.create(5L, request);

        assertEquals("CONFIRMED", view.status().name());
        assertEquals("7", view.resourceId());
        assertEquals("5", view.userId());
        verify(slotMapper).batchInsert(7L, 99L,
                List.of(NOW.toLocalDate().atTime(14, 0), NOW.toLocalDate().atTime(14, 30)));
    }

    @Test
    void approvalRequiredResourceCreatesPendingApproval() {
        resource.setNeedApproval(true);
        when(bookingMapper.insert(any(BookingEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, BookingEntity.class).setId(1L);
            return 1;
        });

        assertEquals(BookingStatus.PENDING_APPROVAL,
                creator.create(5L, request).status());
    }

    @Test
    void slotDuplicateKeyRollsBackAndMapsToSlotConflict() {
        when(bookingMapper.insert(any(BookingEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, BookingEntity.class).setId(99L);
            return 1;
        });
        when(slotMapper.batchInsert(anyLong(), anyLong(), anyList()))
                .thenThrow(new DuplicateKeyException("uk_resource_slot"));

        BizException exception = assertThrows(BizException.class, () -> creator.create(5L, request));

        assertEquals(ErrorCode.BOOKING_ERROR, exception.errorCode);
        assertEquals(BookingMessages.SLOT_CONFLICT, exception.getMessage());
    }

    @Test
    void bookingInsertDuplicateIsAlsoTreatedAsConflict() {
        when(bookingMapper.insert(any(BookingEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_booking_no"));

        BizException exception = assertThrows(BizException.class, () -> creator.create(5L, request));

        assertEquals(BookingMessages.SLOT_CONFLICT, exception.getMessage());
        verify(slotMapper, never()).batchInsert(anyLong(), anyLong(), anyList());
    }

    @Test
    void rechecksCriticalRulesInsideTransaction() {
        when(bookingMapper.insert(any(BookingEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, BookingEntity.class).setId(99L);
            return 1;
        });
        when(slotMapper.batchInsert(anyLong(), anyLong(), anyList())).thenReturn(2);
        creator.create(5L, request);
        verify(guard).checkAll(7L, 5L, 2, request.startTime(), request.endTime());
    }
}
