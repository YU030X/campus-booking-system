package com.yu030x.booking.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yu030x.booking.booking.entity.BookingEntity;
import com.yu030x.booking.booking.mapper.BookingMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-minute no-show scan. Selects only CONFIRMED bookings whose start_time is
 * strictly before now(Asia/Shanghai) - 15 minutes, so a booking at exactly
 * start+15m stays check-in eligible and becomes a candidate only on the first
 * scan strictly after that instant. Each candidate is processed in its own
 * REQUIRES_NEW transaction; one failing item never blocks the remaining ones.
 *
 * <p>Deployment limitation: multiple application instances may run this scan
 * concurrently by design. Correctness relies solely on the conditional
 * CONFIRMED-&gt;NO_SHOW update plus the frozen violation_record.uk_booking_type
 * uniqueness; no distributed lock is configured in P1 (deferred to a separately
 * approved shared-dependency change).</p>
 */
@Component
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NoShowScanTask {
    static final int GRACE_MINUTES = 15;

    private static final Logger log = LoggerFactory.getLogger(NoShowScanTask.class);

    private final BookingMapper bookingMapper;
    private final NoShowItemProcessor processor;
    private final Clock clock;
    private final ZoneId zoneId;

    @Autowired
    public NoShowScanTask(BookingMapper bookingMapper, NoShowItemProcessor processor,
            Clock jwtClock) {
        this(bookingMapper, processor, jwtClock, ZoneId.of("Asia/Shanghai"));
    }

    public NoShowScanTask(BookingMapper bookingMapper, NoShowItemProcessor processor,
            Clock clock, ZoneId zoneId) {
        this.bookingMapper = bookingMapper;
        this.processor = processor;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void scan() {
        ScanSummary summary = scanOnce();
        if (summary.failed() > 0) {
            log.warn("no-show scan finished with failures: {}", summary);
        }
    }

    public ScanSummary scanOnce() {
        LocalDateTime cutoff = LocalDateTime.now(clock.withZone(zoneId)).minusMinutes(GRACE_MINUTES);
        List<BookingEntity> candidates = bookingMapper.selectList(new QueryWrapper<BookingEntity>()
                .select("id", "user_id")
                .eq("status", com.yu030x.booking.common.api.BookingStatus.CONFIRMED.name())
                .lt("start_time", cutoff)
                .orderByAsc("id"));
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (BookingEntity candidate : candidates) {
            try {
                if (processor.process(candidate.getId(), candidate.getUserId())) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception itemFailure) {
                failed++;
                log.warn("no-show item {} failed and was rolled back",
                        candidate.getId(), itemFailure);
            }
        }
        return new ScanSummary(candidates.size(), processed, skipped, failed);
    }

    public record ScanSummary(int candidates, int processed, int skipped, int failed) {
    }
}
