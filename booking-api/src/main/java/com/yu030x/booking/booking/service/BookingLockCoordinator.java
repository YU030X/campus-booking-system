package com.yu030x.booking.booking.service;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class BookingLockCoordinator {
    static final String KEY_PREFIX = "booking:lock:";
    static final long WAIT_SECONDS = 3L;

    private final ObjectProvider<RedissonClient> redissonClientProvider;

    public BookingLockCoordinator(ObjectProvider<RedissonClient> redissonClientProvider) {
        this.redissonClientProvider = redissonClientProvider;
    }

    public <T> T withResourceDateLock(long resourceId, LocalDate bookingDate, Supplier<T> action) {
        RLock lock = lock(resourceId, bookingDate);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw busy();
        } catch (RedisException exception) {
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (RedisException ignored) {
                }
            }
        }
    }

    private RLock lock(long resourceId, LocalDate bookingDate) {
        RedissonClient client = redissonClientProvider.getIfAvailable();
        if (client == null) {
            throw busy();
        }
        return client.getLock(KEY_PREFIX + resourceId + ":" + bookingDate);
    }

    private BizException busy() {
        return new BizException(ErrorCode.BOOKING_ERROR, BookingMessages.SYSTEM_BUSY);
    }
}
