package com.yu030x.booking.violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.user.UserCreditPort;
import com.yu030x.booking.violation.entity.ViolationRecordEntity;
import com.yu030x.booking.violation.mapper.ViolationRecordMapper;
import com.yu030x.booking.violation.port.DefaultViolationPort;
import com.yu030x.booking.violation.port.ViolationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class DefaultViolationPortTest {
    private ViolationRecordMapper mapper;
    private UserCreditPort creditPort;
    private DefaultViolationPort port;

    @BeforeEach
    void setUp() {
        mapper = mock(ViolationRecordMapper.class);
        creditPort = mock(UserCreditPort.class);
        port = new DefaultViolationPort(mapper, creditPort);
    }

    @Test
    void firstNoShowInsertsUniqueRecordThenAppliesFrozenDeductionOnce() {
        when(mapper.selectCount(any())).thenReturn(0L);

        port.recordNoShow(9L, 5L);

        ArgumentCaptor<ViolationRecordEntity> captor =
                ArgumentCaptor.forClass(ViolationRecordEntity.class);
        verify(mapper).insert(captor.capture());
        ViolationRecordEntity inserted = captor.getValue();
        assertThat(inserted.getBookingId()).isEqualTo(9L);
        assertThat(inserted.getUserId()).isEqualTo(5L);
        assertThat(inserted.getViolationType()).isEqualTo("NO_SHOW");
        assertThat(inserted.getScoreChange()).isEqualTo(ViolationPort.NO_SHOW_SCORE_CHANGE)
                .isEqualTo(-10);
        verify(creditPort).applyDeduction(5L, -10);
    }

    @Test
    void existingSameTypeRecordSkipsInsertAndDeduction() {
        when(mapper.selectCount(any())).thenReturn(1L);

        port.recordLateCancel(9L, 5L);

        verify(mapper, never()).insert(any(ViolationRecordEntity.class));
        verify(creditPort, never()).applyDeduction(anyLong(), anyInt());
    }

    @Test
    void racingDuplicateKeyIsTreatedAsAlreadyProcessedWithoutSecondDeduction() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(ViolationRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_booking_type"));

        assertThatCode(() -> port.recordNoShow(9L, 5L)).doesNotThrowAnyException();

        verify(creditPort, never()).applyDeduction(anyLong(), anyInt());
    }

    @Test
    void lateCancelUsesTheFrozenMinusFiveScoreChange() {
        when(mapper.selectCount(any())).thenReturn(0L);

        port.recordLateCancel(9L, 5L);

        ArgumentCaptor<ViolationRecordEntity> captor =
                ArgumentCaptor.forClass(ViolationRecordEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getViolationType()).isEqualTo("LATE_CANCEL");
        assertThat(captor.getValue().getScoreChange()).isEqualTo(ViolationPort.LATE_CANCEL_SCORE_CHANGE)
                .isEqualTo(-5);
        verify(creditPort).applyDeduction(5L, ViolationPort.LATE_CANCEL_SCORE_CHANGE);
    }
}
