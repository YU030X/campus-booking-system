package com.yu030x.booking.user;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.api.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
@ConditionalOnBean(UserMapper.class)
public class AdminUserController {
    private final UserService userService;
    private final BookingPrincipalAccessor principalAccessor;

    AdminUserController(UserService userService, BookingPrincipalAccessor principalAccessor) {
        this.userService = userService;
        this.principalAccessor = principalAccessor;
    }

    @GetMapping
    Result<PageResult<UserView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int pageNumber,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Integer status) {
        return Result.success(userService.listUsers(pageNumber, pageSize, keyword, role, status));
    }

    @PatchMapping("/{id}/status")
    Result<UserView> updateStatus(@PathVariable @Min(1) long id,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        return Result.success(userService.updateStatus(id, request, principalAccessor.current()));
    }
}
