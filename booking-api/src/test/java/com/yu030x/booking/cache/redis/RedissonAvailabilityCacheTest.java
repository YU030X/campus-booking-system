package com.yu030x.booking.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yu030x.booking.cache.port.AvailabilityReadResult;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.ObjectProvider;

class RedissonAvailabilityCacheTest {

    private static final String KEY = "resource:available-slots:42:2026-08-27";

    private RedissonClient client;
    private RBucket<String> bucket;
    private RedissonAvailabilityCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(RedissonClient.class);
        bucket = (RBucket<String>) mock(RBucket.class);
        when(client.<String>getBucket(KEY)).thenReturn(bucket);
        cache = new RedissonAvailabilityCache(new StaticProvider(client));
    }

    @Test
    void hitReturnsStoredStringPayloadUnchanged() {
        String payload = "{\"date\":\"2026-08-27\",\"slots\":[]}";
        when(bucket.get()).thenReturn(payload);
        AvailabilityReadResult result = cache.read(KEY);
        assertSame(AvailabilityReadResult.Status.HIT, result.status());
        assertEquals(payload, result.value());
    }

    @Test
    void absentKeyIsPlainMissWithoutFailureSemantics() {
        when(bucket.get()).thenReturn(null);
        assertEquals(AvailabilityReadResult.Status.MISS, cache.read(KEY).status());
    }

    @Test
    void redisOutageOnReadDegradesToFailureEquivalentToMiss() {
        when(bucket.get()).thenThrow(new RedisException("cluster down"));
        assertEquals(AvailabilityReadResult.Status.FAILURE, cache.read(KEY).status(),
                "outage must map to FAILURE, never propagate");
    }

    @Test
    void writeAppliesDeterministicTtlAndReportsSuccess() {
        boolean ok = cache.write(KEY, "{\"slots\":[\"10:00\"]}");
        org.mockito.Mockito.verify(bucket).set(org.mockito.ArgumentMatchers.eq("{\"slots\":[\"10:00\"]}"),
                org.mockito.ArgumentMatchers.argThat(duration -> {
                    int seconds = (int) duration.toSeconds();
                    return seconds >= 300 && seconds <= 900;
                }));
        assertEquals(true, ok);
    }

    @Test
    void writeFailureReturnsFalseInsteadOfThrowing() {
        org.mockito.Mockito.doThrow(new RedisException("readonly replica"))
                .when(bucket).set(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        assertFalse(cache.write(KEY, "{}"), "write outage must be isolated as false");
    }

    @Test
    void invalidateDeletesThroughTheSameKeySpace() {
        when(bucket.delete()).thenReturn(true);
        assertEquals(true, cache.invalidate(KEY));
        when(bucket.delete()).thenReturn(false);
        assertEquals(false, cache.invalidate("resource:available-slots:42:2026-01-01"));
    }

    @Test
    void invalidateFailureIsSwallowedAsFalse() {
        when(bucket.delete()).thenThrow(new RedisException("connection reset"));
        assertFalse(cache.invalidate(KEY), "delete outage must never reach availability owners");
    }

    @Test
    void disabledOrMissingClientNeverTouchesRedis() {
        RedissonAvailabilityCache disabled = new RedissonAvailabilityCache(new EmptyProvider());
        assertEquals(AvailabilityReadResult.Status.MISS, disabled.read(KEY).status());
        assertFalse(disabled.write(KEY, "{}"));
        assertFalse(disabled.invalidate(KEY));
        verifyNoInteractions(client); // the present-but-unused bean stayed untouched
    }

    @Test
    void invalidKeysNeverReachRedisAndReadAnswersFailure() {
        String[] invalid = new String[]{
                null,
                "",
                "booking:lock:42:2026-08-27",
                "resource:available-slots:0:2026-08-27",
                "resource:available-slots:-7:2026-08-27",
                "resource:available-slots:0042:2026-08-27",
                "resource:available-slots:42:2026-02-30"};

        for (String key : invalid) {
            org.mockito.Mockito.reset(client, bucket);
            assertEquals(AvailabilityReadResult.Status.FAILURE,
                    cache.read(key).status(),
                    () -> "invalid key must answer FAILURE so callers fall back to DB");
            assertFalse(cache.write(key, "{}"), () -> "invalid key write must be refused: " + key);
            assertFalse(cache.invalidate(key), () -> "invalid key delete must be refused: " + key);
            verifyNoInteractions(client, bucket);
        }
    }

    @Test
    void diagnosticsCarryOnlyExceptionClassNameNeverMessageContents() {
        String leaky = "redis://default:p4ssw0rd@host jdbc url secrets";
        when(bucket.get()).thenThrow(new RedisException(leaky));
        cache.read(KEY); // exercises diagnose path; outcome asserted elsewhere

        assertEquals("RedisException", RedissonAvailabilityCache.safeClassName(probe(leaky)),
                "projection must be the class name alone");
        org.junit.jupiter.api.Assertions.assertFalse(
                RedissonAvailabilityCache.safeClassName(probe(leaky)).contains("p4ssw0rd"));
        assertEquals("<none>", RedissonAvailabilityCache.safeClassName(null),
                "null-safe failure projection");
    }

    @Test
    void logEchoGateCollapsesUnvalidatedOrSecretShapedKeys() {
        assertEquals(KEY, RedissonAvailabilityCache.echoKey(KEY), "exact keys may be echoed");
        assertEquals("<none>", RedissonAvailabilityCache.echoKey(null));
        String secretShaped = "redis://default:p4ssw0rd@cache:6379 password=hunter2";
        assertEquals("<invalid>", RedissonAvailabilityCache.echoKey(secretShaped),
                () -> "secret-shaped raw input must never be echoed");
        assertEquals("<invalid>", RedissonAvailabilityCache.echoKey(""));
        assertEquals("<invalid>", RedissonAvailabilityCache.echoKey("password=s3cret"));
    }

    @Test
    void blankPayloadsAreRefusedBeforeRedisAccess() {
        org.mockito.Mockito.reset(client, bucket);
        assertFalse(cache.write(KEY, ""));
        assertFalse(cache.write(KEY, " "));
        assertFalse(cache.write(KEY, "\t\n"));
        verifyNoInteractions(client, bucket);
    }

    private static RedisException probe(String leakyMessage) {
        return new RedisException(leakyMessage);
    }

    private record StaticProvider(RedissonClient c) implements ObjectProvider<RedissonClient> {
        @Override
        public RedissonClient getObject(Object... args) {
            return c;
        }

        @Override
        public RedissonClient getIfAvailable() {
            return c;
        }

        @Override
        public RedissonClient getIfUnique() {
            return c;
        }

        @Override
        public RedissonClient getObject() {
            return c;
        }
    }

    private record EmptyProvider() implements ObjectProvider<RedissonClient> {
        @Override
        public RedissonClient getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedissonClient getIfAvailable() {
            return null;
        }

        @Override
        public RedissonClient getIfUnique() {
            return null;
        }

        @Override
        public RedissonClient getObject() {
            throw new UnsupportedOperationException();
        }
    }
}
