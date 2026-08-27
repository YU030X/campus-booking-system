package com.yu030x.booking.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UserCreditServiceTest {

    private UserMapper userMapper;
    private UserCreditService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new UserCreditService(userMapper, clock);
    }

    @Test
    void positiveScoreChangeIsRejectedBeforeAnySql() {
        assertThatThrownBy(() -> service.applyDeduction(1L, 5))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(exception).hasMessage("invalid parameter");
                });
        verifyNoInteractions(userMapper);
    }

    @Test
    void zeroScoreChangeIsRejectedBeforeAnySql() {
        assertThatThrownBy(() -> service.applyDeduction(1L, 0))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verifyNoInteractions(userMapper);
    }

    @Test
    void nullScoreChangeIsRejectedBeforeAnySql() {
        assertThatThrownBy(() -> service.applyDeduction(1L, null))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verifyNoInteractions(userMapper);
    }

    @Test
    void zeroAffectedRowsMapsToNotFoundWithoutReadBack() {
        whenUpdateReturns(0);

        assertThatThrownBy(() -> service.applyDeduction(7L, -10))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.errorCode).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception).hasMessage("user not found");
                });

        verify(userMapper).applyCreditScoreChange(anyLong(), anyInt(), any(LocalDateTime.class));
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    void successfulDeductionReturnsCreditReadBackInSameTransaction() {
        whenUpdateReturns(1);
        User updated = new User();
        updated.id = 1L;
        updated.creditScore = 90;
        org.mockito.Mockito.when(userMapper.selectById(1L)).thenReturn(updated);

        int resultingCredit = service.applyDeduction(1L, -10);

        assertThat(resultingCredit).isEqualTo(90);
        verify(userMapper).applyCreditScoreChange(1L, -10,
                LocalDateTime.ofInstant(Instant.parse("2026-08-26T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        verify(userMapper).selectById(1L);
    }

    @Test
    void deductionUsesDefaultRequiredPropagationWithoutNewTransactionSemantics() throws Exception {
        Transactional transactional = UserCreditService.class
                .getMethod("applyDeduction", long.class, Integer.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(transactional.value().isEmpty()).isTrue();
        assertThat(transactional.transactionManager().isEmpty()).isTrue();
    }

    private void whenUpdateReturns(int rows) {
        org.mockito.Mockito.when(userMapper.applyCreditScoreChange(anyLong(), anyInt(),
                any(LocalDateTime.class))).thenReturn(rows);
    }
}
