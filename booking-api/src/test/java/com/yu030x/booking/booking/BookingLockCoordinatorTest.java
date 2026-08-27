package com.yu030x.booking.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.booking.service.BookingLockCoordinator;
import com.yu030x.booking.booking.service.BookingMessages;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.ObjectProvider;

class BookingLockCoordinatorTest {
    private static final long RESOURCE_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 26);
    private static final String KEY = "booking:lock:42:2026-08-26";

    private ObjectProvider<RedissonClient> clientProvider;
    private RedissonClient redissonClient;
    private RLock lock;
    private BookingLockCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clientProvider = mock(ObjectProvider.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(clientProvider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        coordinator = new BookingLockCoordinator(clientProvider);
    }

    private final Supplier<String> action = () -> "ok";

    @Test
    void executesActionWhenLockAcquiredAndUnlocksOwnerChecked() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertEquals("ok", coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        verify(lock).unlock();
    }

    @Test
    void busyIsReturnedWhenAcquisitionTimesOut() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        assertEquals(ErrorCode.BOOKING_ERROR, exception.errorCode);
        assertEquals(BookingMessages.SYSTEM_BUSY, exception.getMessage());
        verify(lock, never()).unlock();
    }

    @Test
    void failsClosedOnRedisCommunicationFailure() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenThrow(new RedisException("connection refused"));

        BizException exception = assertThrows(BizException.class,
                () -> coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        assertEquals(ErrorCode.BOOKING_ERROR, exception.errorCode);
        assertEquals(BookingMessages.SYSTEM_BUSY, exception.getMessage());
    }

    @Test
    void failsClosedWithoutRedissonClientConfigured() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        assertEquals(BookingMessages.SYSTEM_BUSY, exception.getMessage());
    }

    @Test
    void interruptedWaitFailsClosedAndRestoresInterruptFlag() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        BizException exception = assertThrows(BizException.class,
                () -> coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        assertEquals(BookingMessages.SYSTEM_BUSY, exception.getMessage());
        assertEquals(true, Thread.interrupted());
    }

    @Test
    void failsClosedWhenGetLockCommunicationFails() {
        when(redissonClient.getLock(KEY)).thenThrow(new RedisException("cluster down"));

        BizException exception = assertThrows(BizException.class,
                () -> coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));

        assertEquals(ErrorCode.BOOKING_ERROR, exception.errorCode);
        assertEquals(BookingMessages.SYSTEM_BUSY, exception.getMessage());
    }

    @Test
    void unlockFailureDoesNotMaskActionResult() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new RedisException("gone")).when(lock).unlock();

        assertEquals("ok", coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));
    }

    @Test
    void differentResourceOrDateUseDifferentKeys() throws Exception {
        RLock otherLock = mock(RLock.class);
        when(redissonClient.getLock("booking:lock:43:2026-08-27")).thenReturn(otherLock);
        when(otherLock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(otherLock.isHeldByCurrentThread()).thenReturn(true);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertEquals("ok", coordinator.withResourceDateLock(RESOURCE_ID, DATE, action));
        verify(redissonClient).getLock(KEY);

        assertEquals("ok", coordinator.withResourceDateLock(43L, LocalDate.of(2026, 8, 27), action));
        verify(redissonClient).getLock("booking:lock:43:2026-08-27");
        verify(otherLock).unlock();
        verify(lock).unlock();
    }
}
