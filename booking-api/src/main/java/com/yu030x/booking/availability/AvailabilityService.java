package com.yu030x.booking.availability;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final int SLOT_MINUTES = 30;

    private final ResourceMapper resourceMapper;
    private final ResourceTimeRuleMapper timeRuleMapper;
    private final ResourceClosureMapper closureMapper;
    private final BookingSlotMapper bookingSlotMapper;
    private final Clock clock;

    @Autowired
    public AvailabilityService(
            @Lazy ResourceMapper resourceMapper,
            @Lazy ResourceTimeRuleMapper timeRuleMapper,
            @Lazy ResourceClosureMapper closureMapper,
            @Lazy BookingSlotMapper bookingSlotMapper) {
        this(resourceMapper, timeRuleMapper, closureMapper, bookingSlotMapper, Clock.system(ZONE_ID));
    }

    AvailabilityService(
            ResourceMapper resourceMapper,
            ResourceTimeRuleMapper timeRuleMapper,
            ResourceClosureMapper closureMapper,
            BookingSlotMapper bookingSlotMapper,
            Clock clock) {
        this.resourceMapper = resourceMapper;
        this.timeRuleMapper = timeRuleMapper;
        this.closureMapper = closureMapper;
        this.bookingSlotMapper = bookingSlotMapper;
        this.clock = clock;
    }

    public AvailabilityVO get(long resourceId, LocalDate date) {
        if (date == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid date");
        }
        ResourceEntity resource = resourceMapper.selectActiveById(resourceId);
        if (resource == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "resource not found");
        }
        if (resource.getStatus() == null || resource.getStatus() != 1) {
            throw new BizException(ErrorCode.RESOURCE_ERROR, "resource unavailable");
        }
        if (resource.getMaxAdvanceDays() == null || resource.getMaxAdvanceDays() < 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid max advance");
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(resource.getMaxAdvanceDays()))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid date");
        }

        AvailabilityVO empty = new AvailabilityVO(String.valueOf(resourceId), date, SLOT_MINUTES, List.of());
        if (closureMapper.selectByScopeAndDate(0, date) != null
                || closureMapper.selectByScopeAndDate(resourceId, date) != null) {
            return empty;
        }

        List<AvailabilityCalculator.Interval> intervals = intervalsFor(resourceId, date);
        List<AvailabilityCalculator.Slot> slots;
        try {
            slots = AvailabilityCalculator.calculate(
                    date,
                    today,
                    now.toLocalDateTime(),
                    resource.getMaxAdvanceDays(),
                    intervals);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "invalid persisted time rule");
        }

        Set<LocalTime> occupiedStarts = occupiedStarts(resourceId, date);
        List<AvailabilityVO.SlotVO> result = slots.stream()
                .map(slot -> new AvailabilityVO.SlotVO(
                        format(slot.start()),
                        format(slot.end()),
                        !occupiedStarts.contains(slot.start())))
                .toList();
        return new AvailabilityVO(String.valueOf(resourceId), date, SLOT_MINUTES, result);
    }

    private List<AvailabilityCalculator.Interval> intervalsFor(long resourceId, LocalDate date) {
        List<AvailabilityCalculator.Interval> intervals = new ArrayList<>();
        List<ResourceTimeRuleEntity> rules = timeRuleMapper.selectActiveByResourceId(resourceId);
        if (rules == null) {
            return intervals;
        }
        int dayOfWeek = date.getDayOfWeek().getValue();
        for (ResourceTimeRuleEntity rule : rules) {
            if (rule != null && rule.getDayOfWeek() != null && rule.getDayOfWeek() == dayOfWeek) {
                intervals.add(new AvailabilityCalculator.Interval(rule.getStartTime(), rule.getEndTime()));
            }
        }
        return intervals;
    }

    private Set<LocalTime> occupiedStarts(long resourceId, LocalDate date) {
        Set<LocalTime> occupied = new HashSet<>();
        List<LocalDateTime> starts = bookingSlotMapper.find(
                resourceId,
                date.atStartOfDay(),
                date.plusDays(1).atStartOfDay());
        if (starts != null) {
            starts.stream().filter(start -> start != null).map(LocalDateTime::toLocalTime).forEach(occupied::add);
        }
        return occupied;
    }

    private String format(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
