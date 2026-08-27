package com.yu030x.booking.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.notification.entity.NotificationEntity;
import com.yu030x.booking.notification.mapper.NotificationMapper;
import com.yu030x.booking.notification.vo.NotificationView;
import java.util.List;

/**
 * Current-user notification reads. Authentication (401) is provided by the
 * global security chain for /api/v1/**; owner scope is enforced here so a
 * foreign or missing id collapses into the same NOT_FOUND without leaking
 * existence, and repeated reads stay idempotent.
 */
public class NotificationService {
    private final NotificationMapper mapper;

    public NotificationService(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<NotificationView> pageForCurrentUser(long userId, int pageNumber, int pageSize) {
        if (userId <= 0 || pageNumber < 1 || pageSize < 1 || pageSize > 100) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        QueryWrapper<NotificationEntity> query = new QueryWrapper<NotificationEntity>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .orderByDesc("id");
        IPage<NotificationEntity> page = mapper.selectPage(new Page<>(pageNumber, pageSize), query);
        List<NotificationView> records = page.getRecords().stream().map(NotificationView::from).toList();
        return new PageResult<>(pageNumber, pageSize, page.getTotal(), records);
    }

    /** Defense in depth: non-positive ids are rejected before any mapper call. */
    public void markReadForCurrentUser(long userId, long notificationId) {
        if (userId <= 0 || notificationId <= 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        int updated = mapper.markRead(notificationId, userId);
        if (updated == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "notification not found");
        }
    }
}
