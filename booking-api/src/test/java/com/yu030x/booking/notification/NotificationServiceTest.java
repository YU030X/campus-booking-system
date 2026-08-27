package com.yu030x.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.yu030x.booking.notification.entity.NotificationEntity;
import com.yu030x.booking.notification.mapper.NotificationMapper;
import com.yu030x.booking.notification.service.NotificationService;
import com.yu030x.booking.notification.vo.NotificationView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationServiceTest {
    private NotificationMapper mapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        service = new NotificationService(mapper);
    }

    private NotificationEntity entity(long id, long userId, LocalDateTime createdAt) {
        NotificationEntity entity = new NotificationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle("预约已通过");
        entity.setContent("您的预约已通过审批");
        entity.setType("BOOKING_APPROVED");
        entity.setBizId(id == 1 ? null : 42L);
        entity.setIsRead(0);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private void stubPage(long total, List<NotificationEntity> records) {
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<NotificationEntity> page = invocation.getArgument(0);
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
    void pagingContractsCurrentUserScopeAndCreatedAtDescIdDescOrdering() {
        stubPage(3, List.of(
                entity(7, 5L, LocalDateTime.of(2026, 8, 27, 10, 0)),
                entity(6, 5L, LocalDateTime.of(2026, 8, 27, 10, 0)),
                entity(5, 5L, LocalDateTime.of(2026, 8, 26, 9, 30))));

        PageResult<NotificationView> result = service.pageForCurrentUser(5L, 2, 20);

        assertThat(result.pageNumber()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(3);
        ArgumentCaptor<Wrapper<NotificationEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        QueryWrapper<NotificationEntity> wrapper = (QueryWrapper<NotificationEntity>) captor.getValue();
        String segment = wrapper.getSqlSegment();
        assertThat(segment).contains("user_id");
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY created_at DESC,id DESC");
    }

    @Test
    void viewMappingKeepsExactFieldsIncludingNullBizId() {
        stubPage(2, List.of(entity(1, 5L, LocalDateTime.of(2026, 8, 27, 8, 15))));

        PageResult<NotificationView> result = service.pageForCurrentUser(5L, 1, 10);

        NotificationView first = result.records().get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.userId()).isEqualTo(5L);
        assertThat(first.title()).isEqualTo("预约已通过");
        assertThat(first.content()).isEqualTo("您的预约已通过审批");
        assertThat(first.type()).isEqualTo("BOOKING_APPROVED");
        assertThat(first.bizId()).isNull();
        assertThat(first.isRead()).isZero();
        assertThat(first.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 27, 8, 15));
    }

    @Test
    void emptyBoxReturnsCanonicalEmptyPageWithZeroTotal() {
        stubPage(0, List.of());

        PageResult<NotificationView> result = service.pageForCurrentUser(5L, 1, 100);

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void ownerReadMarksTheExactUserScopedRow() {
        when(mapper.markRead(9L, 5L)).thenReturn(1);

        service.markReadForCurrentUser(5L, 9L);

        verify(mapper).markRead(9L, 5L);
    }

    @Test
    void repeatedOwnerReadStaysIdempotent() {
        when(mapper.markRead(9L, 5L)).thenReturn(1);

        service.markReadForCurrentUser(5L, 9L);
        service.markReadForCurrentUser(5L, 9L);

        verify(mapper, org.mockito.Mockito.times(2)).markRead(9L, 5L);
    }

    @Test
    void nonPositiveUserOrNotificationIdsAreRejectedBeforeAnyMapperCall() {
        assertThatThrownBy(() -> service.markReadForCurrentUser(0L, 9L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.markReadForCurrentUser(5L, -1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(mapper, never()).markRead(anyLong(), anyLong());

        assertThatThrownBy(() -> service.pageForCurrentUser(-2L, 1, 10))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.pageForCurrentUser(0L, 1, 10))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(mapper, never()).selectPage(any(), any());
    }

    @Test
    void foreignAndMissingIdsCollapseIntoIdenticalNotFoundWithoutLeaking() {
        when(mapper.markRead(77L, 5L)).thenReturn(0);

        assertThatThrownBy(() -> service.markReadForCurrentUser(5L, 77L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode)
                                .as("foreign ownership must not be distinguishable from missing")
                                .isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.markReadForCurrentUser(5L, 999_999L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND));
        verify(mapper, never()).markRead(org.mockito.ArgumentMatchers.eq(999_999L),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
