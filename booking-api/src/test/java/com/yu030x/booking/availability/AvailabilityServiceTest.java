package com.yu030x.booking.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final ResourceMapper resourceMapper = mock(ResourceMapper.class);
    private final ResourceTimeRuleMapper timeRuleMapper = mock(ResourceTimeRuleMapper.class);
    private final ResourceClosureMapper closureMapper = mock(ResourceClosureMapper.class);
    private final BookingSlotMapper bookingSlotMapper = mock(BookingSlotMapper.class);
    private final AvailabilityService service = new AvailabilityService(
            resourceMapper, timeRuleMapper, closureMapper, bookingSlotMapper, CLOCK);

    @Test
    void missingResourceAndUnavailableStatusesStopAtResourceGate() {
        for (int status : new int[] {0, 2}) {
            when(resourceMapper.selectActiveById(1)).thenReturn(resource(status, 2));
            assertThrows(BizException.class, () -> service.get(1, LocalDate.of(2026, 8, 16)));
            verifyNoInteractions(timeRuleMapper, closureMapper, bookingSlotMapper);
        }

        when(resourceMapper.selectActiveById(9)).thenReturn(null);
        assertThrows(BizException.class, () -> service.get(9, LocalDate.of(2026, 8, 16)));
        verifyNoInteractions(timeRuleMapper, closureMapper, bookingSlotMapper);
    }

    @Test
    void closuresShortCircuitRulesAndBookings() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(resourceMapper.selectActiveById(7)).thenReturn(resource(1, 3));
        when(closureMapper.selectByScopeAndDate(0, date)).thenReturn(new ResourceClosureEntity());
        assertTrue(service.get(7, date).slots().isEmpty());

        when(closureMapper.selectByScopeAndDate(0, date)).thenReturn(null);
        when(closureMapper.selectByScopeAndDate(7, date)).thenReturn(new ResourceClosureEntity());
        assertTrue(service.get(7, date).slots().isEmpty());
        verifyNoInteractions(timeRuleMapper, bookingSlotMapper);
    }

    @Test
    void nullRulesAndBookingsAreSafe() {
        when(resourceMapper.selectActiveById(7)).thenReturn(resource(1, 3));
        when(timeRuleMapper.selectActiveByResourceId(7)).thenReturn(null);
        when(bookingSlotMapper.find(anyLong(), any(), any())).thenReturn(null);

        AvailabilityVO result = service.get(7, LocalDate.of(2026, 8, 17));

        assertTrue(result.slots().isEmpty());
    }

    @Test
    void multipleRulesAndOccupiedSlot() {
        when(resourceMapper.selectActiveById(7)).thenReturn(resource(1, 3));
        when(timeRuleMapper.selectActiveByResourceId(7)).thenReturn(Arrays.asList(
                rule(1, LocalTime.of(9, 0), LocalTime.of(10, 0)),
                rule(1, LocalTime.of(10, 0), LocalTime.of(11, 0))));
        when(bookingSlotMapper.find(anyLong(), any(), any()))
                .thenReturn(List.of(LocalDateTime.of(2026, 8, 17, 9, 30)));

        AvailabilityVO result = service.get(7, LocalDate.of(2026, 8, 17));

        assertEquals(4, result.slots().size());
        assertFalse(result.slots().get(1).available());
    }

    @Test
    void sameDayPastSlotsFiltered() {
        when(resourceMapper.selectActiveById(7)).thenReturn(resource(1, 3));
        when(timeRuleMapper.selectActiveByResourceId(7))
                .thenReturn(List.of(rule(6, LocalTime.of(8, 0), LocalTime.of(10, 0))));
        when(bookingSlotMapper.find(anyLong(), any(), any())).thenReturn(List.of());

        AvailabilityVO result = service.get(7, LocalDate.of(2026, 8, 15));

        assertTrue(result.slots().stream().allMatch(slot -> slot.startTime().compareTo("09:00") > 0));
    }

    @Test
    void invalidPersistedRuleMapsInternalError() {
        when(resourceMapper.selectActiveById(7)).thenReturn(resource(1, 3));
        when(timeRuleMapper.selectActiveByResourceId(7))
                .thenReturn(List.of(rule(6, LocalTime.of(9, 15), LocalTime.of(10, 0))));

        BizException exception = assertThrows(
                BizException.class,
                () -> service.get(7, LocalDate.of(2026, 8, 15)));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.errorCode);
        verifyNoInteractions(bookingSlotMapper);
    }

    private ResourceEntity resource(int status, int maxAdvanceDays) {
        ResourceEntity resource = new ResourceEntity();
        resource.setStatus(status);
        resource.setMaxAdvanceDays(maxAdvanceDays);
        return resource;
    }

    private ResourceTimeRuleEntity rule(int dayOfWeek, LocalTime start, LocalTime end) {
        ResourceTimeRuleEntity rule = new ResourceTimeRuleEntity();
        rule.setDayOfWeek(dayOfWeek);
        rule.setStartTime(start);
        rule.setEndTime(end);
        return rule;
    }
}
