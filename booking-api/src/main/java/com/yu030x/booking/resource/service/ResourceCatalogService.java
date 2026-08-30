package com.yu030x.booking.resource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.cache.invalidate.AfterCommitInvalidationCoordinator;
import com.yu030x.booking.cache.invalidate.AvailabilityInvalidationRequest;
import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.ResourceRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.log.annotation.OperationLog;
import com.yu030x.booking.resource.mapper.ResourceCategoryMapper;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import com.yu030x.booking.resource.vo.ClosureVO;
import com.yu030x.booking.resource.vo.ResourceVO;
import com.yu030x.booking.resource.vo.TimeRuleVO;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceCatalogService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ResourceMapper resourceMapper;
    private final ResourceCategoryMapper categoryMapper;
    private final ResourceTimeRuleMapper timeRuleMapper;
    private final ResourceClosureMapper closureMapper;
    private final AfterCommitInvalidationCoordinator invalidation;
    private final Clock clock;

    @Autowired
    public ResourceCatalogService(
            @Lazy ResourceMapper resourceMapper,
            @Lazy ResourceCategoryMapper categoryMapper,
            @Lazy ResourceTimeRuleMapper timeRuleMapper,
            @Lazy ResourceClosureMapper closureMapper,
            ObjectProvider<AfterCommitInvalidationCoordinator> invalidationProvider) {
        this(resourceMapper, categoryMapper, timeRuleMapper, closureMapper,
                invalidationProvider.getIfAvailable(), Clock.system(SHANGHAI));
    }

    public ResourceCatalogService(
            ResourceMapper resourceMapper,
            ResourceCategoryMapper categoryMapper,
            ResourceTimeRuleMapper timeRuleMapper,
            ResourceClosureMapper closureMapper) {
        this(resourceMapper, categoryMapper, timeRuleMapper, closureMapper,
                null, Clock.system(SHANGHAI));
    }

    public ResourceCatalogService(
            ResourceMapper resourceMapper,
            ResourceCategoryMapper categoryMapper,
            ResourceTimeRuleMapper timeRuleMapper,
            ResourceClosureMapper closureMapper,
            AfterCommitInvalidationCoordinator invalidation,
            Clock clock) {
        this.resourceMapper = resourceMapper;
        this.categoryMapper = categoryMapper;
        this.timeRuleMapper = timeRuleMapper;
        this.closureMapper = closureMapper;
        this.invalidation = invalidation;
        this.clock = clock;
    }

    public PageResult<ResourceVO> list(
            String rawPageNumber,
            String rawPageSize,
            String rawCategoryId,
            String rawStatus,
            String rawKeyword) {
        int pageNumber = ResourceInputSupport.queryInteger(rawPageNumber, 1, 1, Integer.MAX_VALUE);
        int pageSize = ResourceInputSupport.queryInteger(rawPageSize, 10, 1, 100);
        Long categoryId = null;
        if (rawCategoryId != null && !rawCategoryId.isBlank()) {
            categoryId = ResourceInputSupport.decimalId(rawCategoryId.trim(), false);
        }
        Integer status = rawStatus == null
                ? null
                : ResourceInputSupport.queryInteger(rawStatus, 0, 0, 2);
        String keyword = ResourceInputSupport.trimmedToNull(rawKeyword, 100);

        Page<ResourceEntity> page = new Page<>(pageNumber, pageSize);
        resourceMapper.selectResourcePage(page, categoryId, status, keyword);
        List<ResourceVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(pageNumber, pageSize, page.getTotal(), records);
    }

    public ResourceVO detail(String rawId) {
        long id = ResourceInputSupport.decimalId(rawId, true);
        if (id == 0) {
            throw resourceNotFound();
        }
        ResourceEntity entity = resourceMapper.selectActiveById(id);
        if (entity == null) {
            throw resourceNotFound();
        }
        return toVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public ResourceVO create(ResourceRequest request) {
        ResourceEntity entity = normalize(request);
        requireActiveCategory(entity.getCategoryId());
        resourceMapper.insert(entity);
        return toVO(resourceMapper.selectActiveById(entity.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public ResourceVO update(String rawId, ResourceRequest request) {
        long id = ResourceInputSupport.decimalId(rawId, true);
        ResourceEntity previous = id == 0 ? null : resourceMapper.selectActiveForUpdate(id);
        if (previous == null) {
            throw resourceNotFound();
        }
        ResourceEntity replacement = normalize(request);
        replacement.setId(id);
        requireActiveCategory(replacement.getCategoryId());
        if (resourceMapper.updateActive(replacement) != 1) {
            throw resourceNotFound();
        }
        scheduleWindow(id, maxAdvance(previous), maxAdvance(replacement), "resource_update");
        return toVO(resourceMapper.selectActiveById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public ResourceVO updateStatus(String rawId, String rawStatus) {
        long id = ResourceInputSupport.decimalId(rawId, true);
        if (rawStatus == null) {
            throw ResourceInputSupport.invalid();
        }
        int status = ResourceInputSupport.queryInteger(rawStatus, -1, 0, 2);
        ResourceEntity resource = id == 0 ? null : resourceMapper.selectActiveForUpdate(id);
        if (resource == null) {
            throw resourceNotFound();
        }
        if (resourceMapper.updateActiveStatus(id, status) != 1) {
            throw resourceNotFound();
        }
        scheduleWindow(id, maxAdvance(resource), maxAdvance(resource), "resource_status");
        return toVO(resourceMapper.selectActiveById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public List<TimeRuleVO> replaceTimeRules(String rawId, List<TimeRuleRequest> requests) {
        long resourceId = ResourceInputSupport.decimalId(rawId, true);
        List<NormalizedRule> normalized = normalizeRules(requests);
        ResourceEntity resource = resourceId == 0 ? null : resourceMapper.selectActiveForUpdate(resourceId);
        if (resource == null) {
            throw resourceNotFound();
        }

        timeRuleMapper.logicalDeleteActiveByResourceId(resourceId);
        for (NormalizedRule rule : normalized) {
            ResourceTimeRuleEntity entity = new ResourceTimeRuleEntity();
            entity.setResourceId(resourceId);
            entity.setDayOfWeek(rule.dayOfWeek());
            entity.setStartTime(rule.startTime());
            entity.setEndTime(rule.endTime());
            timeRuleMapper.insert(entity);
        }
        scheduleWindow(resourceId, maxAdvance(resource), maxAdvance(resource), "resource_time_rule");
        return timeRuleMapper.selectActiveByResourceId(resourceId).stream().map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public ClosureVO createClosure(String rawResourceId, ClosureRequest request) {
        long resourceId = ResourceInputSupport.decimalId(rawResourceId, true);
        if (request == null) {
            throw ResourceInputSupport.invalid();
        }
        LocalDate closureDate = ResourceInputSupport.date(request.closureDate());
        String reason = ResourceInputSupport.trimmedToNull(request.reason(), 200);
        if (resourceId != 0 && resourceMapper.selectActiveById(resourceId) == null) {
            throw resourceNotFound();
        }
        if (closureMapper.selectByScopeAndDate(resourceId, closureDate) != null) {
            throw closureConflict();
        }

        ResourceClosureEntity entity = new ResourceClosureEntity();
        entity.setResourceId(resourceId);
        entity.setClosureDate(closureDate);
        entity.setReason(reason);
        try {
            closureMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw closureConflict();
        }
        scheduleClosure(resourceId, closureDate, "resource_closure_create");
        return toVO(closureMapper.selectByIdAndScope(entity.getId(), resourceId));
    }

    @Transactional(rollbackFor = Exception.class)
    @OperationLog("resource_update")
    public void deleteClosure(String rawResourceId, String rawClosureId) {
        long resourceId = ResourceInputSupport.decimalId(rawResourceId, true);
        long closureId = ResourceInputSupport.decimalId(rawClosureId, false);
        ResourceClosureEntity closure = closureMapper.selectByIdAndScope(closureId, resourceId);
        if (closure == null) {
            throw closureNotFound();
        }
        if (closureMapper.physicalDeleteByIdAndScope(closureId, resourceId) != 1) {
            throw closureNotFound();
        }
        scheduleClosure(resourceId, closure.getClosureDate(), "resource_closure_delete");
    }

    private ResourceEntity normalize(ResourceRequest request) {
        if (request == null) {
            throw ResourceInputSupport.invalid();
        }
        ResourceEntity entity = new ResourceEntity();
        entity.setCategoryId(ResourceInputSupport.decimalId(request.categoryId(), true));
        entity.setName(ResourceInputSupport.requiredTrimmed(request.name(), 100));
        entity.setLocation(ResourceInputSupport.trimmedToNull(request.location(), 200));
        if (request.capacity() != null && request.capacity() <= 0) {
            throw ResourceInputSupport.invalid();
        }
        entity.setCapacity(request.capacity());
        entity.setDescription(ResourceInputSupport.description(request.description()));
        entity.setImages(ResourceInputSupport.trimmedToNull(request.images(), 1_000));
        entity.setNeedApproval(Boolean.TRUE.equals(request.needApproval()));
        entity.setMaxAdvanceDays(ResourceInputSupport.bounded(request.maxAdvanceDays(), 7, 0, 365));
        int minDuration = ResourceInputSupport.positiveMultipleOfThirty(request.minDurationMinutes(), 30);
        int maxDuration = ResourceInputSupport.positiveMultipleOfThirty(request.maxDurationMinutes(), 120);
        if (minDuration > maxDuration) {
            throw ResourceInputSupport.invalid();
        }
        entity.setMinDurationMinutes(minDuration);
        entity.setMaxDurationMinutes(maxDuration);
        entity.setStatus(ResourceInputSupport.bounded(request.status(), 1, 0, 2));
        return entity;
    }

    private List<NormalizedRule> normalizeRules(List<TimeRuleRequest> requests) {
        if (requests == null) {
            throw ResourceInputSupport.invalid();
        }
        List<NormalizedRule> normalized = new ArrayList<>();
        for (TimeRuleRequest request : requests) {
            if (request == null || request.dayOfWeek() == null
                    || request.dayOfWeek() < 1 || request.dayOfWeek() > 7) {
                throw ResourceInputSupport.invalid();
            }
            LocalTime start = ResourceInputSupport.halfHourTime(request.startTime());
            LocalTime end = ResourceInputSupport.halfHourTime(request.endTime());
            if (!start.isBefore(end)) {
                throw ResourceInputSupport.invalid();
            }
            normalized.add(new NormalizedRule(request.dayOfWeek(), start, end));
        }
        normalized.sort(Comparator.comparing(NormalizedRule::dayOfWeek)
                .thenComparing(NormalizedRule::startTime)
                .thenComparing(NormalizedRule::endTime));
        for (int index = 1; index < normalized.size(); index++) {
            NormalizedRule previous = normalized.get(index - 1);
            NormalizedRule current = normalized.get(index);
            if (previous.dayOfWeek() == current.dayOfWeek()
                    && current.startTime().isBefore(previous.endTime())) {
                throw ResourceInputSupport.invalid();
            }
        }
        return normalized;
    }

    private void requireActiveCategory(long categoryId) {
        if (categoryId == 0 || categoryMapper.selectActiveForUpdate(categoryId) == null) {
            throw new BizException(ErrorCode.RESOURCE_ERROR, "category is unavailable");
        }
    }

    private ResourceVO toVO(ResourceEntity entity) {
        return new ResourceVO(
                decimalString(entity.getId()), decimalString(entity.getCategoryId()),
                entity.getName(), entity.getLocation(),
                entity.getCapacity(), entity.getDescription(), entity.getImages(), entity.getNeedApproval(),
                entity.getMaxAdvanceDays(), entity.getMinDurationMinutes(), entity.getMaxDurationMinutes(),
                entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private TimeRuleVO toVO(ResourceTimeRuleEntity entity) {
        return new TimeRuleVO(
                decimalString(entity.getId()), decimalString(entity.getResourceId()),
                entity.getDayOfWeek(), entity.getStartTime(),
                entity.getEndTime(), entity.getCreatedAt());
    }

    private ClosureVO toVO(ResourceClosureEntity entity) {
        return new ClosureVO(
                decimalString(entity.getId()), decimalString(entity.getResourceId()),
                entity.getClosureDate(), entity.getReason(),
                entity.getCreatedAt());
    }

    private String decimalString(Long value) {
        return value == null ? null : value.toString();
    }

    private BizException resourceNotFound() {
        return new BizException(ErrorCode.NOT_FOUND, "resource not found");
    }

    private BizException closureNotFound() {
        return new BizException(ErrorCode.NOT_FOUND, "closure not found");
    }

    private BizException closureConflict() {
        return new BizException(ErrorCode.RESOURCE_ERROR, "closure already exists");
    }

    private int maxAdvance(ResourceEntity resource) {
        return resource == null || resource.getMaxAdvanceDays() == null
                ? 0
                : Math.max(0, resource.getMaxAdvanceDays());
    }

    private void scheduleWindow(long resourceId, int previousDays, int currentDays, String origin) {
        if (invalidation == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        int inclusiveDays = Math.max(previousDays, currentDays);
        for (int offset = 0; offset <= inclusiveDays; offset++) {
            schedule(resourceId, today.plusDays(offset), origin);
        }
    }

    private void scheduleClosure(long resourceId, LocalDate date, String origin) {
        if (invalidation == null || date == null) {
            return;
        }
        if (resourceId != 0) {
            schedule(resourceId, date, origin);
            return;
        }
        List<Long> enabledResourceIds = resourceMapper.selectEnabledIdsForCacheInvalidation();
        if (enabledResourceIds == null) {
            return;
        }
        enabledResourceIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .forEach(id -> schedule(id, date, origin));
    }

    private void schedule(long resourceId, LocalDate date, String origin) {
        invalidation.scheduleAfterCommit(new AvailabilityInvalidationRequest(
                AvailabilityCacheKey.of(resourceId, date), origin));
    }

    private record NormalizedRule(int dayOfWeek, LocalTime startTime, LocalTime endTime) {}
}
