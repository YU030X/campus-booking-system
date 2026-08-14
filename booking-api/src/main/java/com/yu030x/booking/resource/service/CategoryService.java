package com.yu030x.booking.resource.service;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.dto.CategoryRequest;
import com.yu030x.booking.resource.entity.ResourceCategoryEntity;
import com.yu030x.booking.resource.mapper.ResourceCategoryMapper;
import com.yu030x.booking.resource.vo.CategoryVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private final ResourceCategoryMapper categoryMapper;

    public CategoryService(@Lazy ResourceCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public List<CategoryVO> tree() {
        List<ResourceCategoryEntity> rows = categoryMapper.selectActiveTreeRows();
        Map<Long, List<ResourceCategoryEntity>> children = new HashMap<>();
        for (ResourceCategoryEntity row : rows) {
            children.computeIfAbsent(row.getParentId(), ignored -> new ArrayList<>()).add(row);
        }
        return children.getOrDefault(0L, List.of()).stream()
                .map(row -> toTree(row, children, new HashSet<>()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CategoryVO create(CategoryRequest request) {
        NormalizedCategory normalized = normalize(request);
        validateParentAndCycle(normalized.parentId(), null);

        ResourceCategoryEntity entity = new ResourceCategoryEntity();
        entity.setName(normalized.name());
        entity.setParentId(normalized.parentId());
        entity.setSortOrder(normalized.sortOrder());
        entity.setIcon(normalized.icon());
        categoryMapper.insert(entity);
        return toVO(categoryMapper.selectActiveById(entity.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public CategoryVO update(String rawId, CategoryRequest request) {
        long id = ResourceInputSupport.decimalId(rawId, true);
        ResourceCategoryEntity existing = categoryMapper.selectActiveForUpdate(id);
        if (existing == null) {
            throw notFound();
        }
        NormalizedCategory normalized = normalize(request);
        validateParentAndCycle(normalized.parentId(), id);

        existing.setName(normalized.name());
        existing.setParentId(normalized.parentId());
        existing.setSortOrder(normalized.sortOrder());
        existing.setIcon(normalized.icon());
        if (categoryMapper.updateActive(existing) != 1) {
            throw notFound();
        }
        return toVO(categoryMapper.selectActiveById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String rawId) {
        long id = ResourceInputSupport.decimalId(rawId, true);
        if (categoryMapper.selectActiveForUpdate(id) == null) {
            throw notFound();
        }
        if (categoryMapper.countActiveChildren(id) > 0 || categoryMapper.countActiveResources(id) > 0) {
            throw new BizException(ErrorCode.RESOURCE_ERROR, "category is referenced");
        }
        if (categoryMapper.logicalDeleteActive(id) != 1) {
            throw notFound();
        }
    }

    private NormalizedCategory normalize(CategoryRequest request) {
        if (request == null) {
            throw ResourceInputSupport.invalid();
        }
        String name = ResourceInputSupport.requiredTrimmed(request.name(), 50);
        long parentId = request.parentId() == null || request.parentId().isBlank()
                ? 0L
                : ResourceInputSupport.decimalId(request.parentId().trim(), true);
        int sortOrder = ResourceInputSupport.bounded(request.sortOrder(), 0, -100_000, 100_000);
        String icon = ResourceInputSupport.trimmedToNull(request.icon(), 255);
        return new NormalizedCategory(name, parentId, sortOrder, icon);
    }

    private void validateParentAndCycle(long parentId, Long targetId) {
        if (parentId == 0) {
            return;
        }
        Set<Long> visited = new HashSet<>();
        long currentId = parentId;
        boolean first = true;
        while (currentId != 0) {
            if ((targetId != null && currentId == targetId) || !visited.add(currentId)) {
                throw ResourceInputSupport.invalid();
            }
            ResourceCategoryEntity current = first
                    ? categoryMapper.selectActiveForUpdate(currentId)
                    : categoryMapper.selectActiveById(currentId);
            first = false;
            if (current == null) {
                throw ResourceInputSupport.invalid();
            }
            currentId = current.getParentId();
        }
    }

    private CategoryVO toTree(
            ResourceCategoryEntity entity,
            Map<Long, List<ResourceCategoryEntity>> children,
            Set<Long> path) {
        if (!path.add(entity.getId())) {
            throw new IllegalStateException("category cycle in database");
        }
        List<CategoryVO> childViews = children.getOrDefault(entity.getId(), List.of()).stream()
                .map(child -> toTree(child, children, path))
                .toList();
        path.remove(entity.getId());
        return toVO(entity, childViews);
    }

    private CategoryVO toVO(ResourceCategoryEntity entity) {
        return toVO(entity, List.of());
    }

    private CategoryVO toVO(ResourceCategoryEntity entity, List<CategoryVO> children) {
        return new CategoryVO(
                decimalString(entity.getId()),
                entity.getName(),
                decimalString(entity.getParentId()),
                entity.getSortOrder(),
                entity.getIcon(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                children);
    }

    private String decimalString(Long value) {
        return value == null ? null : value.toString();
    }

    private BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "category not found");
    }

    private record NormalizedCategory(String name, long parentId, int sortOrder, String icon) {}
}
