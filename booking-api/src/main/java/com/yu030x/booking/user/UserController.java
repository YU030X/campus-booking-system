package com.yu030x.booking.user;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UserController {
    private final UserService userService;
    private final BookingPrincipalAccessor principalAccessor;

    UserController(UserService userService, BookingPrincipalAccessor principalAccessor) {
        this.userService = userService;
        this.principalAccessor = principalAccessor;
    }

    @GetMapping
    Result<UserView> me() {
        return Result.success(userService.currentUser(principalAccessor.current()));
    }

    @PutMapping
    Result<UserView> replace(@Valid @RequestBody ProfileUpdateRequest request) {
        return Result.success(userService.replaceProfile(principalAccessor.current(), request));
    }
}
