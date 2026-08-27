package com.yu030x.booking.violation.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.violation.entity.ViolationRecordEntity;
import com.yu030x.booking.violation.mapper.ViolationRecordMapper;
import com.yu030x.booking.violation.vo.ViolationView;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ViolationService {
    private final ViolationRecordMapper violationMapper;

    public ViolationService(ViolationRecordMapper violationMapper) {
        this.violationMapper = violationMapper;
    }

    public PageResult<ViolationView> pageForCurrentUser(long userId, int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        QueryWrapper<ViolationRecordEntity> query = new QueryWrapper<ViolationRecordEntity>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .orderByDesc("id");
        IPage<ViolationRecordEntity> page =
                violationMapper.selectPage(new Page<>(pageNumber, pageSize), query);
        List<ViolationView> records = page.getRecords().stream().map(ViolationView::from).toList();
        return new PageResult<>(pageNumber, pageSize, page.getTotal(), records);
    }
}
