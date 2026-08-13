package com.yu030x.booking.resource.controller;

import com.yu030x.booking.common.api.Result;
import com.yu030x.booking.resource.dto.CategoryRequest;
import com.yu030x.booking.resource.service.CategoryService;
import com.yu030x.booking.resource.vo.CategoryVO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/categories")
    @PreAuthorize("isAuthenticated()")
    public Result<List<CategoryVO>> categories() {
        return Result.success(categoryService.tree());
    }

    @PostMapping("/admin/categories")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CategoryVO> create(@RequestBody(required = false) CategoryRequest request) {
        return Result.success(categoryService.create(request));
    }

    @PutMapping("/admin/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CategoryVO> update(
            @PathVariable String id,
            @RequestBody(required = false) CategoryRequest request) {
        return Result.success(categoryService.update(id, request));
    }

    @DeleteMapping("/admin/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable String id) {
        categoryService.delete(id);
        return Result.success(null);
    }
}
