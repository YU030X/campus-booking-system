package com.yu030x.booking.booking.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class DefaultBookingAdminReads implements BookingAdminReads {
    private final BookingMapper bookingMapper;

    @Autowired
    public DefaultBookingAdminReads(@Lazy BookingMapper bookingMapper) {
        this.bookingMapper = bookingMapper;
    }

    @Override
    public PageResult<BookingView> pagePendingApprovals(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid page bounds");
        }
        IPage<BookingEntity> page = bookingMapper.selectPendingApprovalPage(new Page<>(pageNumber, pageSize));
        return new PageResult<>(pageNumber, pageSize, page.getTotal(),
                page.getRecords().stream().map(BookingView::from).toList());
    }
}
