package com.yu030x.booking.violation.port;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yu030x.booking.user.UserCreditPort;
import com.yu030x.booking.violation.entity.ViolationRecordEntity;
import com.yu030x.booking.violation.mapper.ViolationRecordMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultViolationPort implements ViolationPort {

    static final String TYPE_NO_SHOW = "NO_SHOW";
    static final String TYPE_LATE_CANCEL = "LATE_CANCEL";

    private final ViolationRecordMapper violationMapper;
    private final UserCreditPort creditPort;

    public DefaultViolationPort(ViolationRecordMapper violationMapper, UserCreditPort creditPort) {
        this.violationMapper = violationMapper;
        this.creditPort = creditPort;
    }

    @Override
    public void recordNoShow(long bookingId, long userId) {
        record(bookingId, userId, TYPE_NO_SHOW, NO_SHOW_SCORE_CHANGE);
    }

    @Override
    public void recordLateCancel(long bookingId, long userId) {
        record(bookingId, userId, TYPE_LATE_CANCEL, LATE_CANCEL_SCORE_CHANGE);
    }

    private void record(long bookingId, long userId, String type, int scoreChange) {
        if (countByType(bookingId, type) > 0) {
            return;
        }
        ViolationRecordEntity entity = new ViolationRecordEntity();
        entity.setUserId(userId);
        entity.setBookingId(bookingId);
        entity.setViolationType(type);
        entity.setScoreChange(scoreChange);
        try {
            violationMapper.insert(entity);
        } catch (DuplicateKeyException alreadyProcessedByRace) {
            return;
        }
        creditPort.applyDeduction(userId, scoreChange);
    }

    private long countByType(long bookingId, String type) {
        return violationMapper.selectCount(new QueryWrapper<ViolationRecordEntity>()
                .eq("booking_id", bookingId)
                .eq("violation_type", type));
    }
}
