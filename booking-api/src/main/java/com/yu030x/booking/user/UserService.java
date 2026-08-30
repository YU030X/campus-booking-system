package com.yu030x.booking.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.auth.TextNormalizer;
import com.yu030x.booking.auth.security.BookingPrincipal;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.log.annotation.OperationLog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UserService {
    private final UserMapper userMapper;
    private final Clock clock;

    UserService(UserMapper userMapper, Clock jwtClock) {
        this.userMapper = userMapper;
        this.clock = jwtClock;
    }

    public UserView currentUser(BookingPrincipal principal) {
        return UserView.from(requireUser(principal.id()));
    }

    @Transactional
    public UserView replaceProfile(BookingPrincipal principal, ProfileUpdateRequest request) {
        User user = requireUser(principal.id());
        user.realName = request.realName();
        user.phone = request.phone();
        user.email = request.email();
        user.avatar = request.avatar();
        user.updatedAt = LocalDateTime.now(clock);
        userMapper.updateById(user);
        return UserView.from(requireUser(user.id));
    }

    public PageResult<UserView> listUsers(int pageNumber, int pageSize, String keyword,
            UserRole role, Integer status) {
        String normalizedKeyword = TextNormalizer.optional(keyword);
        if (normalizedKeyword != null && normalizedKeyword.length() > 100) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        if (status != null && !UserStatus.isValid(status)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        if (normalizedKeyword != null) {
            query.and(group -> group.like("username", normalizedKeyword)
                    .or().like("real_name", normalizedKeyword)
                    .or().like("student_no", normalizedKeyword));
        }
        query.eq(role != null, "role", role);
        query.eq(status != null, "status", status);
        query.orderByDesc("created_at").orderByDesc("id");
        Page<User> page = userMapper.selectPage(Page.of(pageNumber, pageSize), query);
        List<UserView> records = page.getRecords().stream().map(UserView::from).toList();
        return new PageResult<>(pageNumber, pageSize, page.getTotal(), records);
    }

    @Transactional
    @OperationLog("user_status_update")
    public UserView updateStatus(long id, UserStatusUpdateRequest request, BookingPrincipal principal) {
        if (id == principal.id() && request.status() == UserStatus.DISABLED.value()) {
            throw new BizException(ErrorCode.USER_ERROR, "administrator cannot disable self");
        }
        User user = requireUser(id);
        if (!request.status().equals(user.status)) {
            user.status = request.status();
            user.updatedAt = LocalDateTime.now(clock);
            userMapper.updateById(user);
        }
        return UserView.from(requireUser(id));
    }

    private User requireUser(long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "user not found");
        }
        return user;
    }
}
