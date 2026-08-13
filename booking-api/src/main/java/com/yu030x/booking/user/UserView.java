package com.yu030x.booking.user;

import java.time.LocalDateTime;

public record UserView(String id, String username, String realName, String studentNo, String phone,
        String email, String avatar, UserRole role, Integer creditScore, Integer status,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static UserView from(User user) {
        return new UserView(user.id.toString(), user.username, user.realName, user.studentNo, user.phone,
                user.email, user.avatar, user.role, user.creditScore, user.status, user.createdAt, user.updatedAt);
    }
}
