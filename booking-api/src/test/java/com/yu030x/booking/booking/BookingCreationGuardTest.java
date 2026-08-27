package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.service.BookingCreationGuard;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingCreationGuardTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDate.of(2026, 8, 26).atTime(10, 0);

    private ResourceMapper resourceMapper;
    private ResourceTimeRuleMapper timeRuleMapper;
    private ResourceClosureMapper closureMapper;
    private UserMapper userMapper;
    private BookingMapper bookingMapper;
    private BookingCreationGuard guard;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(ResourceMapper.class);
        timeRuleMapper = mock(ResourceTimeRuleMapper.class);
        closureMapper = mock(ResourceClosureMapper.class);
        userMapper = mock(UserMapper.class);
        bookingMapper = mock(BookingMapper.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        guard = new BookingCreationGuard(resourceMapper, timeRuleMapper, closureMapper,
                userMapper, bookingMapper, clock);

        lenient().when(resourceMapper.selectActiveById(7L)).thenReturn(resource());
        lenient().when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of(rule()));
        lenient().when(closureMapper.selectByScopeAndDate(0L, NOW.toLocalDate())).thenReturn(null);
        lenient().when(closureMapper.selectByScopeAndDate(7L, NOW.toLocalDate())).thenReturn(null);
        User user = new User();
        user.id = 5L;
        user.status = 1;
        lenient().when(userMapper.selectById(5L)).thenReturn(user);
        lenient().when(bookingMapper.countActiveBlacklist(5L, NOW.toLocalDate())).thenReturn(0L);
        lenient().when(bookingMapper.countActiveByUser(5L)).thenReturn(0L);
    }

    private ResourceEntity resource() {
        ResourceEntity resource = new ResourceEntity();
        resource.setId(7L);
        resource.setStatus(1);
        resource.setNeedApproval(false);
        resource.setMaxAdvanceDays(7);
        resource.setMinDurationMinutes(30);
        resource.setMaxDurationMinutes(120);
        resource.setCapacity(10);
        return resource;
    }

    private ResourceTimeRuleEntity rule() {
        int dayOfWeek = NOW.getDayOfWeek().getValue();
        ResourceTimeRuleEntity rule = new ResourceTimeRuleEntity();
        rule.setId(1L);
        rule.setResourceId(7L);
        rule.setDayOfWeek(dayOfWeek);
        rule.setStartTime(LocalTime.of(8, 0));
        rule.setEndTime(LocalTime.of(22, 0));
        rule.setDeleted(0);
        return rule;
    }

    private LocalDateTime start() {
        return NOW.toLocalDate().atTime(14, 0);
    }

    private void assertRejected(int attendeeCount, LocalDateTime start, LocalDateTime end,
            ErrorCode expectedCode) {
        BizException exception = assertThrows(BizException.class,
                () -> guard.checkAll(7L, 5L, attendeeCount, start, end));
        assertEquals(expectedCode, exception.errorCode);
    }

    @Test
    void passesAllRulesForValidRequest() {
        ResourceEntity resource = guard.checkAll(7L, 5L, 3, start(), start().plusHours(2));
        assertSame(resource, resourceMapper.selectActiveById(7L));
    }

    @Test
    void missingResourceIsNotFoundAndInactiveResourceIsResourceError() {
        when(resourceMapper.selectActiveById(7L)).thenReturn(null);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.NOT_FOUND);
        ResourceEntity resource = resource();
        resource.setStatus(0);
        when(resourceMapper.selectActiveById(7L)).thenReturn(resource);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.RESOURCE_ERROR);
        resource.setStatus(2);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.RESOURCE_ERROR);
    }

    @Test
    void durationMustStayWithinConfiguredBoundsInclusively() {
        guard.checkAll(7L, 5L, 1, start(), start().plusMinutes(30));
        guard.checkAll(7L, 5L, 1, start(), start().plusMinutes(120));
        assertRejected(1, start(), start().plusMinutes(29), ErrorCode.BOOKING_ERROR);
        assertRejected(1, start(), start().plusMinutes(150), ErrorCode.BOOKING_ERROR);
    }

    @Test
    void advanceDaysBoundaryIsInclusive() {
        ResourceEntity resource = resource();
        resource.setMaxAdvanceDays(2);
        when(resourceMapper.selectActiveById(7L)).thenReturn(resource);
        LocalDateTime lastDay = NOW.toLocalDate().plusDays(2).atTime(9, 0);
        ResourceTimeRuleEntity lastDayRule = rule();
        lastDayRule.setDayOfWeek(lastDay.getDayOfWeek().getValue());
        lenient().when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of(lastDayRule));
        guard.checkAll(7L, 5L, 1, lastDay, lastDay.plusMinutes(30));
        LocalDateTime tooLate = NOW.toLocalDate().plusDays(3).atTime(9, 0);
        assertRejected(1, tooLate, tooLate.plusMinutes(30), ErrorCode.INVALID_PARAMETER);
    }

    @Test
    void capacityIsEnforcedOnlyWhenConfigured() {
        assertRejected(11, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
        guard.checkAll(7L, 5L, 10, start(), start().plusMinutes(30));
        ResourceEntity noCapacity = resource();
        noCapacity.setCapacity(null);
        when(resourceMapper.selectActiveById(7L)).thenReturn(noCapacity);
        guard.checkAll(7L, 5L, 999, start(), start().plusMinutes(30));
    }

    @Test
    void globalAndResourceClosuresAreRejected() {
        when(closureMapper.selectByScopeAndDate(0L, NOW.toLocalDate()))
                .thenAnswer(invocation -> new com.yu030x.booking.resource.entity.ResourceClosureEntity());
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
        when(closureMapper.selectByScopeAndDate(0L, NOW.toLocalDate())).thenReturn(null);
        when(closureMapper.selectByScopeAndDate(7L, NOW.toLocalDate()))
                .thenAnswer(invocation -> new com.yu030x.booking.resource.entity.ResourceClosureEntity());
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
    }

    @Test
    void intervalMustBeFullyContainedByOneOpenRule() {
        guard.checkAll(7L, 5L, 1, start(), start().plusHours(2));
        LocalTime from = LocalTime.of(14, 15);
        ResourceTimeRuleEntity shifted = rule();
        shifted.setStartTime(from);
        when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of(shifted));
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);

        when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of());
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);

        ResourceTimeRuleEntity otherDay = rule();
        otherDay.setDayOfWeek(NOW.getDayOfWeek().getValue() % 7 + 1);
        when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of(otherDay));
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
    }

    @Test
    void userMustExistAndBeEnabled() {
        when(userMapper.selectById(5L)).thenReturn(null);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.NOT_FOUND);
        User disabled = new User();
        disabled.id = 5L;
        disabled.status = 0;
        when(userMapper.selectById(5L)).thenReturn(disabled);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.USER_ERROR);
    }

    @Test
    void blacklistBoundsAreInclusiveOnBothEnds() {
        when(bookingMapper.countActiveBlacklist(5L, NOW.toLocalDate())).thenReturn(1L);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
        when(bookingMapper.countActiveBlacklist(5L, NOW.toLocalDate())).thenReturn(0L);
        guard.checkAll(7L, 5L, 1, start(), start().plusMinutes(30));
    }

    @Test
    void activeBookingLimitUsesDefaultMaximum() {
        when(bookingMapper.countActiveByUser(5L)).thenReturn(2L);
        guard.checkAll(7L, 5L, 1, start(), start().plusMinutes(30));
        when(bookingMapper.countActiveByUser(5L)).thenReturn(3L);
        assertRejected(1, start(), start().plusMinutes(30), ErrorCode.BOOKING_ERROR);
    }
}
