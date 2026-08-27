package com.yu030x.booking.user;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UserCreditService implements UserCreditPort {

    private final UserMapper userMapper;
    private final Clock clock;

    UserCreditService(UserMapper userMapper, Clock jwtClock) {
        this.userMapper = userMapper;
        this.clock = jwtClock;
    }

    @Override
    @Transactional
    public int applyDeduction(long userId, Integer scoreChange) {
        if (scoreChange == null || scoreChange >= 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter");
        }
        if (userMapper.applyCreditScoreChange(userId, scoreChange, LocalDateTime.now(clock)) != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "user not found");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "user not found");
        }
        return user.creditScore;
    }
}
