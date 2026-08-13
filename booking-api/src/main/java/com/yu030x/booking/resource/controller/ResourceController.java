package com.yu030x.booking.resource.controller;

import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.ResourceRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import com.yu030x.booking.resource.vo.ClosureVO;
import com.yu030x.booking.resource.vo.ResourceVO;
import com.yu030x.booking.resource.vo.TimeRuleVO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ResourceController {
    private final ResourceCatalogService resourceService;

    public ResourceController(ResourceCatalogService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping("/resources")
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<ResourceVO>> resources(
            @RequestParam(required = false) String pageNumber,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.success(resourceService.list(pageNumber, pageSize, categoryId, status, keyword));
    }

    @GetMapping("/resources/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<ResourceVO> detail(@PathVariable String id) {
        return Result.success(resourceService.detail(id));
    }

    @PostMapping("/admin/resources")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ResourceVO> create(@RequestBody(required = false) ResourceRequest request) {
        return Result.success(resourceService.create(request));
    }

    @PutMapping("/admin/resources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ResourceVO> update(
            @PathVariable String id,
            @RequestBody(required = false) ResourceRequest request) {
        return Result.success(resourceService.update(id, request));
    }

    @PatchMapping("/admin/resources/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ResourceVO> updateStatus(
            @PathVariable String id,
            @RequestParam(required = false) String status) {
        return Result.success(resourceService.updateStatus(id, status));
    }

    @PutMapping("/admin/resources/{id}/time-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<TimeRuleVO>> replaceTimeRules(
            @PathVariable String id,
            @RequestBody(required = false) List<TimeRuleRequest> requests) {
        return Result.success(resourceService.replaceTimeRules(id, requests));
    }

    @PostMapping("/admin/resources/{id}/closures")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ClosureVO> createClosure(
            @PathVariable String id,
            @RequestBody(required = false) ClosureRequest request) {
        return Result.success(resourceService.createClosure(id, request));
    }

    @DeleteMapping("/admin/resources/{id}/closures/{closureId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteClosure(
            @PathVariable String id,
            @PathVariable String closureId) {
        resourceService.deleteClosure(id, closureId);
        return Result.success(null);
    }
}
