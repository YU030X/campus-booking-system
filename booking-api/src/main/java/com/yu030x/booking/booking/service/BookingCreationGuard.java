package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import com.yu030x.booking.user.User;
import com.yu030x.booking.user.UserMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class BookingCreationGuard {
    static final int DEFAULT_MAX_ACTIVE_BOOKINGS = 3;
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ResourceMapper resourceMapper;
    private final ResourceTimeRuleMapper timeRuleMapper;
    private final ResourceClosureMapper closureMapper;
    private final UserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final Clock clock;

    @Autowired
    public BookingCreationGuard(
            @Lazy ResourceMapper resourceMapper,
            @Lazy ResourceTimeRuleMapper timeRuleMapper,
            @Lazy ResourceClosureMapper closureMapper,
            @Lazy UserMapper userMapper,
            @Lazy BookingMapper bookingMapper) {
        this(resourceMapper, timeRuleMapper, closureMapper, userMapper, bookingMapper,
                Clock.system(SHANGHAI));
    }

    public BookingCreationGuard(
            ResourceMapper resourceMapper,
            ResourceTimeRuleMapper timeRuleMapper,
            ResourceClosureMapper closureMapper,
            UserMapper userMapper,
            BookingMapper bookingMapper,
            Clock clock) {
        this.resourceMapper = resourceMapper;
        this.timeRuleMapper = timeRuleMapper;
        this.closureMapper = closureMapper;
        this.userMapper = userMapper;
        this.bookingMapper = bookingMapper;
        this.clock = clock;
    }

    public ResourceEntity checkAll(long resourceId, long userId, int attendeeCount,
            LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate bookingDate = start.toLocalDate();

        ResourceEntity resource = resourceMapper.selectActiveById(resourceId);
        if (resource == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "resource not found");
        }
        if (resource.getStatus() == null || resource.getStatus() != 1) {
            throw new BizException(ErrorCode.RESOURCE_ERROR, "resource unavailable");
        }
        if (resource.getMaxAdvanceDays() == null || resource.getMaxAdvanceDays() < 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid max advance days");
        }

        long durationMinutes = Duration.between(start, end).toMinutes();
        if (resource.getMinDurationMinutes() != null && durationMinutes < resource.getMinDurationMinutes()) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "duration below minimum");
        }
        if (resource.getMaxDurationMinutes() != null && durationMinutes > resource.getMaxDurationMinutes()) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "duration above maximum");
        }

        if (bookingDate.isAfter(today.plusDays(resource.getMaxAdvanceDays()))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "date beyond max advance days");
        }

        if (resource.getCapacity() != null && attendeeCount > resource.getCapacity()) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "attendee count above capacity");
        }

        if (closureMapper.selectByScopeAndDate(0L, bookingDate) != null
                || closureMapper.selectByScopeAndDate(resourceId, bookingDate) != null) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "resource closed on that date");
        }

        if (!containedByOpenRule(resourceId, start, end)) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "outside open rules");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "user not found");
        }
        if (user.status == null || user.status != 1) {
            throw new BizException(ErrorCode.USER_ERROR, "user disabled");
        }

        if (bookingMapper.countActiveBlacklist(userId, today) > 0) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "user blacklisted");
        }

        if (bookingMapper.countActiveByUser(userId) >= DEFAULT_MAX_ACTIVE_BOOKINGS) {
            throw new BizException(ErrorCode.BOOKING_ERROR, "active booking limit reached");
        }

        return resource;
    }

    private boolean containedByOpenRule(long resourceId, LocalDateTime start, LocalDateTime end) {
        List<ResourceTimeRuleEntity> rules = timeRuleMapper.selectActiveByResourceId(resourceId);
        if (rules == null) {
            return false;
        }
        int dayOfWeek = start.getDayOfWeek().getValue();
        LocalTime from = start.toLocalTime();
        LocalTime to = end.toLocalTime();
        for (ResourceTimeRuleEntity rule : rules) {
            if (rule == null || rule.getDayOfWeek() == null || rule.getDayOfWeek() != dayOfWeek) {
                continue;
            }
            if (!rule.getStartTime().isAfter(from) && !rule.getEndTime().isBefore(to)) {
                return true;
            }
        }
        return false;
    }
}
