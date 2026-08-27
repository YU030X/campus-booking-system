package com.yu030x.booking.approval.service;

import com.yu030x.booking.approval.entity.ApprovalRecordEntity;
import com.yu030x.booking.approval.mapper.ApprovalRecordMapper;
import com.yu030x.booking.booking.service.BookingActionOutcome;
import com.yu030x.booking.booking.service.BookingActions;
import com.yu030x.booking.booking.service.BookingAdminReads;
import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.violation.port.ViolationPort;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T09 orchestration boundary. Every action opens one REQUIRED transaction that
 * the T07 BookingActions conditional transition, the T07 slot release (winner
 * reject/cancel only), the approval-record insert, and the T10 ViolationPort
 * late-cancel handoff all join; a failure rolls the whole unit back. Booking
 * and booking_slot persistence is never touched directly.
 */
@Service
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalService {
    static final String ACTION_APPROVE = "APPROVE";
    static final String ACTION_REJECT = "REJECT";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final BookingAdminReads bookingAdminReads;
    private final BookingActions bookingActions;
    private final ViolationPort violationPort;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final Clock clock;

    @Autowired
    public ApprovalService(BookingAdminReads bookingAdminReads,
            BookingActions bookingActions,
            ViolationPort violationPort,
            ApprovalRecordMapper approvalRecordMapper,
            Clock jwtClock) {
        this(bookingAdminReads, bookingActions, violationPort, approvalRecordMapper,
                jwtClock, SHANGHAI);
    }

    public ApprovalService(BookingAdminReads bookingAdminReads,
            BookingActions bookingActions,
            ViolationPort violationPort,
            ApprovalRecordMapper approvalRecordMapper,
            Clock clock, ZoneId zoneId) {
        this.bookingAdminReads = bookingAdminReads;
        this.bookingActions = bookingActions;
        this.violationPort = violationPort;
        this.approvalRecordMapper = approvalRecordMapper;
        this.clock = clock.withZone(zoneId);
    }

    public PageResult<BookingView> pendingPage(int pageNumber, int pageSize) {
        return bookingAdminReads.pagePendingApprovals(pageNumber, pageSize);
    }

    @Transactional
    public BookingView approve(long bookingId, long approverId, String comment) {
        BookingActionOutcome outcome = bookingActions.approve(bookingId);
        return conclude(outcome, bookingId, approverId, ACTION_APPROVE, comment,
                "booking is not pending approval");
    }

    @Transactional
    public BookingView reject(long bookingId, long approverId, String comment) {
        BookingActionOutcome outcome = bookingActions.reject(bookingId);
        return conclude(outcome, bookingId, approverId, ACTION_REJECT, comment,
                "booking is not pending approval");
    }

    @Transactional
    public BookingView cancel(long userId, long bookingId, String cancelReason) {
        LocalDateTime actionTime = LocalDateTime.now(clock);
        BookingActionOutcome outcome = bookingActions.cancel(bookingId, userId, actionTime, cancelReason);
        return switch (outcome.result()) {
            case WINNER -> {
                if (isLateCancel(outcome.booking().startTime(), actionTime)) {
                    violationPort.recordLateCancel(bookingId, userId);
                }
                yield outcome.booking();
            }
            case ALREADY_COMPLETED -> outcome.booking();
            case ILLEGAL_TRANSITION -> throw new BizException(
                    ErrorCode.BOOKING_ERROR, "booking cannot be cancelled");
            case NOT_FOUND -> throw new BizException(ErrorCode.NOT_FOUND, "booking not found");
        };
    }

    public boolean isLateCancel(LocalDateTime startTime, LocalDateTime actionTime) {
        return actionTime.isAfter(startTime.minusHours(2));
    }

    private BookingView conclude(BookingActionOutcome outcome, long bookingId, long approverId,
            String action, String comment, String illegalMessage) {
        return switch (outcome.result()) {
            case WINNER -> {
                insertRecord(bookingId, approverId, action, comment);
                yield outcome.booking();
            }
            case ALREADY_COMPLETED -> outcome.booking();
            case ILLEGAL_TRANSITION -> throw new BizException(ErrorCode.BOOKING_ERROR, illegalMessage);
            case NOT_FOUND -> throw new BizException(ErrorCode.NOT_FOUND, "booking not found");
        };
    }

    private void insertRecord(long bookingId, long approverId, String action, String comment) {
        ApprovalRecordEntity record = new ApprovalRecordEntity();
        record.setBookingId(bookingId);
        record.setApproverId(approverId);
        record.setAction(action);
        record.setComment(comment);
        approvalRecordMapper.insert(record);
    }
}
