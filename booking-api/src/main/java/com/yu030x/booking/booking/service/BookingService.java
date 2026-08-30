package com.yu030x.booking.booking.service;

import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.booking.dto.CreateBookingRequest;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.BookingStatus;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.log.annotation.OperationLog;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.user.UserRole;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
    private static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");

    private final BookingMapper bookingMapper;
    private final BookingLockCoordinator lockCoordinator;
    private final BookingCreator creator;

    public BookingService(@Lazy BookingMapper bookingMapper,
            BookingLockCoordinator lockCoordinator, BookingCreator creator) {
        this.bookingMapper = bookingMapper;
        this.lockCoordinator = lockCoordinator;
        this.creator = creator;
    }

    @OperationLog("booking_create")
    public BookingView create(BookingPrincipal principal, CreateBookingRequest request) {
        requireStudent(principal);
        BookingIntervalValidator.ParsedInterval interval =
                BookingIntervalValidator.validate(request, java.time.LocalDateTime.now(SHANGHAI));
        return lockCoordinator.withResourceDateLock(
                interval.resourceId(),
                interval.date(),
                () -> creator.create(principal.id(), request));
    }

    public PageResult<BookingView> list(long userId, int pageNumber, int pageSize, BookingStatus status) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid page bounds");
        }
        IPage<BookingEntity> page = bookingMapper.selectUserPage(
                new Page<>(pageNumber, pageSize), userId, status == null ? null : status.name());
        return new PageResult<>(pageNumber, pageSize, page.getTotal(),
                page.getRecords().stream().map(BookingView::from).toList());
    }

    public BookingView detail(long userId, long id) {
        BookingEntity entity = bookingMapper.selectActiveByIdAndUser(id, userId);
        if (entity == null) {
            throw notFound();
        }
        return BookingView.from(entity);
    }

    private void requireStudent(BookingPrincipal principal) {
        if (principal == null || principal.role() != UserRole.STUDENT) {
            throw new BizException(ErrorCode.FORBIDDEN, "student role required");
        }
    }

    private BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "booking not found");
    }
}
