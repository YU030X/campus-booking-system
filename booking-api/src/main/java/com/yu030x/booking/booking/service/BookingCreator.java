package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.mapper.BookingOccupancyMapper;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.entity.ResourceEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingCreator {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final BookingMapper bookingMapper;
    private final BookingOccupancyMapper slotMapper;
    private final BookingCreationGuard guard;
    private final Clock clock;

    @Autowired
    public BookingCreator(
            @Lazy BookingMapper bookingMapper,
            @Lazy BookingOccupancyMapper slotMapper,
            @Lazy BookingCreationGuard guard) {
        this(bookingMapper, slotMapper, guard, Clock.system(SHANGHAI));
    }

    public BookingCreator(BookingMapper bookingMapper, BookingOccupancyMapper slotMapper,
            BookingCreationGuard guard, Clock clock) {
        this.bookingMapper = bookingMapper;
        this.slotMapper = slotMapper;
        this.guard = guard;
        this.clock = clock;
    }

    @Transactional
    public BookingView create(long userId, CreateBookingRequest request) {
        BookingIntervalValidator.ParsedInterval interval =
                BookingIntervalValidator.validate(request, LocalDateTime.now(clock));
        ResourceEntity resource = guard.checkAll(
                interval.resourceId(), userId, request.attendeeCount(),
                interval.start(), interval.end());

        LocalDateTime now = LocalDateTime.now(clock);
        BookingStatus status = Boolean.TRUE.equals(resource.getNeedApproval())
                ? BookingStatus.PENDING_APPROVAL
                : BookingStatus.CONFIRMED;

        BookingEntity booking = new BookingEntity();
        booking.setBookingNo(BookingNumberGenerator.generate(now));
        booking.setUserId(userId);
        booking.setResourceId(interval.resourceId());
        booking.setStartTime(interval.start());
        booking.setEndTime(interval.end());
        booking.setPurpose(request.purpose());
        booking.setAttendeeCount(request.attendeeCount());
        booking.setStatus(status);
        booking.setDeleted(0);
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);

        try {
            bookingMapper.insert(booking);
            List<LocalDateTime> slots = com.yu030x.booking.booking.BookingSlotSplitter.split(
                    interval.start(), interval.end());
            slotMapper.batchInsert(interval.resourceId(), booking.getId(), slots);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.BOOKING_ERROR, BookingMessages.SLOT_CONFLICT);
        }
        return BookingView.from(booking);
    }
}
