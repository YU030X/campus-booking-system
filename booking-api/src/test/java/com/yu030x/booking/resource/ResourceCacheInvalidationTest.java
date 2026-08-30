package com.yu030x.booking.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.cache.invalidate.AfterCommitInvalidationCoordinator;
import com.yu030x.booking.cache.invalidate.AvailabilityInvalidationRequest;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.mapper.ResourceCategoryMapper;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceCacheInvalidationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-29T16:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ResourceMapper resourceMapper;
    private ResourceTimeRuleMapper timeRuleMapper;
    private ResourceClosureMapper closureMapper;
    private AfterCommitInvalidationCoordinator invalidation;
    private ResourceCatalogService service;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(ResourceMapper.class);
        timeRuleMapper = mock(ResourceTimeRuleMapper.class);
        closureMapper = mock(ResourceClosureMapper.class);
        invalidation = mock(AfterCommitInvalidationCoordinator.class);
        service = new ResourceCatalogService(resourceMapper, mock(ResourceCategoryMapper.class),
                timeRuleMapper, closureMapper, invalidation, CLOCK);
    }

    @Test
    void statusAndTimeRuleMutationsInvalidateTheInclusiveAvailabilityWindow() {
        ResourceEntity resource = resource(7L, 2);
        when(resourceMapper.selectActiveForUpdate(7L)).thenReturn(resource);
        when(resourceMapper.updateActiveStatus(7L, 0)).thenReturn(1);
        when(resourceMapper.selectActiveById(7L)).thenReturn(resource);
        when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of());

        service.updateStatus("7", "0");
        service.replaceTimeRules("7", List.of(new TimeRuleRequest(1, "08:00:00", "09:00:00")));

        for (int offset = 0; offset <= 2; offset++) {
            String key = "resource:available-slots:7:" + TODAY.plusDays(offset);
            verify(invalidation).scheduleAfterCommit(
                    new AvailabilityInvalidationRequest(key, "resource_status"));
            verify(invalidation).scheduleAfterCommit(
                    new AvailabilityInvalidationRequest(key, "resource_time_rule"));
        }
        verify(invalidation, times(6)).scheduleAfterCommit(any());
    }

    @Test
    void resourceClosureUsesExactDateAndGlobalClosureEnumeratesEnabledResources() {
        LocalDate date = TODAY.plusDays(3);
        when(resourceMapper.selectActiveById(7L)).thenReturn(resource(7L, 2));
        when(resourceMapper.selectEnabledIdsForCacheInvalidation()).thenReturn(List.of(7L, 9L));
        doAnswer(invocation -> {
            ResourceClosureEntity entity = invocation.getArgument(0);
            entity.setId(41L);
            return 1;
        }).when(closureMapper).insert(any(ResourceClosureEntity.class));
        ResourceClosureEntity resourceClosure = closure(41L, 7L, date);
        ResourceClosureEntity globalClosure = closure(42L, 0L, date);
        when(closureMapper.selectByIdAndScope(41L, 7L)).thenReturn(resourceClosure);
        when(closureMapper.selectByIdAndScope(42L, 0L)).thenReturn(globalClosure);
        when(closureMapper.physicalDeleteByIdAndScope(41L, 7L)).thenReturn(1);
        when(closureMapper.physicalDeleteByIdAndScope(42L, 0L)).thenReturn(1);

        service.createClosure("7", new ClosureRequest(date.toString(), "maintenance"));
        service.deleteClosure("7", "41");
        service.deleteClosure("0", "42");

        String resourceKey = "resource:available-slots:7:" + date;
        verify(invalidation).scheduleAfterCommit(
                new AvailabilityInvalidationRequest(resourceKey, "resource_closure_create"));
        verify(invalidation, times(2)).scheduleAfterCommit(
                new AvailabilityInvalidationRequest(resourceKey, "resource_closure_delete"));
        verify(invalidation).scheduleAfterCommit(new AvailabilityInvalidationRequest(
                "resource:available-slots:9:" + date, "resource_closure_delete"));
    }

    private ResourceEntity resource(long id, int maxAdvanceDays) {
        ResourceEntity resource = new ResourceEntity();
        resource.setId(id);
        resource.setCategoryId(1L);
        resource.setStatus(1);
        resource.setMaxAdvanceDays(maxAdvanceDays);
        return resource;
    }

    private ResourceClosureEntity closure(long id, long resourceId, LocalDate date) {
        ResourceClosureEntity closure = new ResourceClosureEntity();
        closure.setId(id);
        closure.setResourceId(resourceId);
        closure.setClosureDate(date);
        return closure;
    }
}
