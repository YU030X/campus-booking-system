package com.yu030x.booking.booking.service;

import com.yu030x.booking.common.config.RedisProperties;
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
    private final ObjectProvider<RedisProperties> redisPropertiesProvider;

    public BookingLockCoordinator(ObjectProvider<RedissonClient> redissonClientProvider) {
        this(redissonClientProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingLockCoordinator(ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<RedisProperties> redisPropertiesProvider) {
        this.redissonClientProvider = redissonClientProvider;
        this.redisPropertiesProvider = redisPropertiesProvider;
    }

    public <T> T withResourceDateLock(long resourceId, LocalDate bookingDate, Supplier<T> action) {
        RedisProperties redisProperties =
                redisPropertiesProvider == null ? null : redisPropertiesProvider.getIfAvailable();
        if (redisProperties != null && redisProperties.isLockDisabled()) {
            // Unique-index-only mode: the database constraint alone defends slot
            // correctness; the Redis lock is skipped. Used only by the T13
            // unique-index-only load round via BOOKING_REDIS_LOCK_DISABLED=true.
            return action.get();
        }
        RLock lock;
        try {
            lock = lock(resourceId, bookingDate);
        } catch (RedisException exception) {
            throw busy();
        }
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
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RedisException ignored) {
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
