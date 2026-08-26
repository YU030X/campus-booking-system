package com.yu030x.booking.violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.violation.entity.ViolationRecordEntity;
import com.yu030x.booking.violation.mapper.ViolationRecordMapper;
import com.yu030x.booking.violation.service.ViolationService;
import com.yu030x.booking.violation.vo.ViolationView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ViolationServiceTest {
    private ViolationRecordMapper mapper;
    private ViolationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ViolationRecordMapper.class);
        service = new ViolationService(mapper);
    }

    private ViolationRecordEntity entity(long id) {
        ViolationRecordEntity entity = new ViolationRecordEntity();
        entity.setId(id);
        entity.setUserId(5L);
        entity.setBookingId(9L + id);
        entity.setViolationType("NO_SHOW");
        entity.setScoreChange(-10);
        entity.setRemark(null);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 26, 12 - (int) id, 0, 0));
        return entity;
    }

    @SuppressWarnings("unchecked")
    private void stubPage(long total, List<ViolationRecordEntity> records) {
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<ViolationRecordEntity> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    @Test
    void invalidPageBoundsAreRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.pageForCurrentUser(5L, 0, 10))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.pageForCurrentUser(5L, 1, 0))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.pageForCurrentUser(5L, 1, 101))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(mapper, never()).selectPage(any(), any());
    }

    @Test
    void pageMapsExactViewFieldsWithCurrentUserScopingAndStableOrdering() {
        stubPage(2, List.of(entity(1), entity(2)));

        PageResult<ViolationView> result = service.pageForCurrentUser(5L, 2, 20);

        assertThat(result.pageNumber()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.records()).hasSize(2);
        ViolationView first = result.records().get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.userId()).isEqualTo(5L);
        assertThat(first.bookingId()).isEqualTo(10L);
        assertThat(first.violationType()).isEqualTo("NO_SHOW");
        assertThat(first.scoreChange()).isEqualTo(-10);

        ArgumentCaptor<Wrapper<ViolationRecordEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String segment = ((QueryWrapper<ViolationRecordEntity>) captor.getValue()).getSqlSegment();
        assertThat(segment).contains("user_id");
        assertThat(segment).contains("ORDER BY created_at DESC,id DESC");
    }

    @Test
    void emptyHistoryReturnsCanonicalEmptyPageWithZeroTotal() {
        stubPage(0, List.of());

        PageResult<ViolationView> result = service.pageForCurrentUser(5L, 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
    }
}
