package com.yu030x.booking.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yu030x.booking.resource.config.ResourceMapperConfiguration;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.entity.ResourceCategoryEntity;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.resource.mapper.ResourceCategoryMapper;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import com.yu030x.booking.resource.service.CategoryService;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResourceServiceIdContractTest {
    private static final long LARGE_ID = 9_007_199_254_740_993L;

    @Test
    void resourceConfigurationRegistersEveryMapperLazily() {
        new ApplicationContextRunner()
                .withUserConfiguration(ResourceMapperConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertLazyMapper(context, "resourceCategoryMapper");
                    assertLazyMapper(context, "resourceClosureMapper");
                    assertLazyMapper(context, "resourceMapper");
                    assertLazyMapper(context, "resourceTimeRuleMapper");
                });
    }

    @Test
    void convertsEveryResponseIdToDecimalStringAtServiceBoundary() {
        ResourceCategoryMapper categoryMapper = mock(ResourceCategoryMapper.class);
        ResourceCategoryEntity category = new ResourceCategoryEntity();
        category.setId(LARGE_ID);
        category.setParentId(0L);
        category.setName("Rooms");
        category.setSortOrder(0);
        when(categoryMapper.selectActiveTreeRows()).thenReturn(List.of(category));

        CategoryService categoryService = new CategoryService(categoryMapper);
        var categoryView = categoryService.tree().get(0);
        assertEquals(Long.toString(LARGE_ID), categoryView.id());
        assertEquals("0", categoryView.parentId());

        ResourceMapper resourceMapper = mock(ResourceMapper.class);
        ResourceTimeRuleMapper timeRuleMapper = mock(ResourceTimeRuleMapper.class);
        ResourceClosureMapper closureMapper = mock(ResourceClosureMapper.class);
        ResourceCatalogService resourceService = new ResourceCatalogService(
                resourceMapper, categoryMapper, timeRuleMapper, closureMapper);

        ResourceEntity resource = new ResourceEntity();
        resource.setId(LARGE_ID);
        resource.setCategoryId(LARGE_ID - 1);
        when(resourceMapper.selectActiveById(LARGE_ID)).thenReturn(resource);
        when(resourceMapper.selectActiveForUpdate(LARGE_ID)).thenReturn(resource);
        assertEquals(Long.toString(LARGE_ID), resourceService.detail(Long.toString(LARGE_ID)).id());
        assertEquals(Long.toString(LARGE_ID - 1),
                resourceService.detail(Long.toString(LARGE_ID)).categoryId());

        ResourceTimeRuleEntity rule = new ResourceTimeRuleEntity();
        rule.setId(LARGE_ID - 2);
        rule.setResourceId(LARGE_ID);
        rule.setDayOfWeek(1);
        rule.setStartTime(LocalTime.of(8, 0));
        rule.setEndTime(LocalTime.of(9, 0));
        when(timeRuleMapper.selectActiveByResourceId(LARGE_ID)).thenReturn(List.of(rule));
        var ruleView = resourceService.replaceTimeRules(
                Long.toString(LARGE_ID),
                List.of(new TimeRuleRequest(1, "08:00:00", "09:00:00"))).get(0);
        assertEquals(Long.toString(LARGE_ID - 2), ruleView.id());
        assertEquals(Long.toString(LARGE_ID), ruleView.resourceId());

        ResourceClosureEntity closure = new ResourceClosureEntity();
        closure.setId(LARGE_ID - 3);
        closure.setResourceId(LARGE_ID);
        closure.setClosureDate(LocalDate.of(2026, 8, 14));
        doAnswer(invocation -> {
            ResourceClosureEntity inserted = invocation.getArgument(0);
            inserted.setId(LARGE_ID - 3);
            return 1;
        }).when(closureMapper).insert(any(ResourceClosureEntity.class));
        when(closureMapper.selectByIdAndScope(LARGE_ID - 3, LARGE_ID)).thenReturn(closure);
        var closureView = resourceService.createClosure(
                Long.toString(LARGE_ID), new ClosureRequest("2026-08-14", null));
        assertEquals(Long.toString(LARGE_ID - 3), closureView.id());
        assertEquals(Long.toString(LARGE_ID), closureView.resourceId());
    }

    private void assertLazyMapper(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String beanName) {
        assertTrue(context.getBeanFactory().containsBeanDefinition(beanName));
        assertTrue(context.getBeanFactory().getBeanDefinition(beanName).isLazyInit());
    }
}
