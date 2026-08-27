package com.yu030x.booking.cache.redis;

import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import com.yu030x.booking.cache.port.AvailabilityReadResult;
import com.yu030x.booking.cache.ttl.AvailabilityCacheTtl;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Redisson-backed adapter over the availability Cache Aside port. Values are
 * plain strings (the owner owns serialization), the TTL follows the frozen
 * deterministic algorithm, and every Redis fault collapses into
 * miss/false outcomes with a bounded diagnostic so availability reads can fall
 * back to their database calculation without ever touching T07's lock path.
 *
 * <p>Invalid keys (per {@link AvailabilityCacheKey#isExact}) never reach
 * Redis: reads answer {@link AvailabilityReadResult#failure()} — deliberately
 * indistinguishable from an outage so callers only ever fall back to their DB
 * computation — while writes and deletions answer {@code false}. Nothing is
 * thrown for invalid input.</p>
 *
 * <p>Diagnostics intentionally carry no raw failure message: vendor messages
 * can embed connection strings, credentials or JDBC URIs. Only the exception
 * class name, a fixed operation tag and the bounded (already validated or
 * explicitly truncated) cache key appear in logs.</p>
 *
 * <p>When the flag is disabled or no client exists, Redis is never contacted.</p>
 */
public class RedissonAvailabilityCache implements AvailabilityCachePort {

    private static final Logger LOG = LoggerFactory.getLogger(RedissonAvailabilityCache.class);
    private static final int KEY_ECHO_LIMIT = 80;

    private final ObjectProvider<RedissonClient> clients;

    public RedissonAvailabilityCache(ObjectProvider<RedissonClient> clients) {
        this.clients = clients;
    }

    @Override
    public AvailabilityReadResult read(String key) {
        if (!AvailabilityCacheKey.isExact(key)) {
            rejectInvalid("read", key);
            return AvailabilityReadResult.failure();
        }
        RedissonClient client = safeClient();
        if (client == null) {
            return AvailabilityReadResult.miss();
        }
        try {
            String value = client.<String>getBucket(key).get();
            return value == null ? AvailabilityReadResult.miss() : AvailabilityReadResult.hit(value);
        } catch (Throwable outageOrSerdeFault) {
            diagnose("read", key, outageOrSerdeFault);
            return AvailabilityReadResult.failure();
        }
    }

    @Override
    public boolean write(String key, String value) {
        if (!AvailabilityCacheKey.isExact(key)) {
            rejectInvalid("write", key);
            return false;
        }
        if (value == null || value.isBlank()) {
            // A blank projection must never poison the cache with an unusable payload.
            return false;
        }
        RedissonClient client = safeClient();
        if (client == null) {
            return false;
        }
        try {
            client.<String>getBucket(key).set(value,
                    Duration.ofSeconds(AvailabilityCacheTtl.ttlSeconds(key)));
            return true;
        } catch (Throwable outageOrWriteFault) {
            diagnose("write", key, outageOrWriteFault);
            return false;
        }
    }

    @Override
    public boolean invalidate(String key) {
        if (!AvailabilityCacheKey.isExact(key)) {
            rejectInvalid("invalidate", key);
            return false;
        }
        RedissonClient client = safeClient();
        if (client == null) {
            return false;
        }
        try {
            client.<String>getBucket(key).delete();
            return true;
        } catch (Throwable outageOrDeleteFault) {
            diagnose("invalidate", key, outageOrDeleteFault);
            return false;
        }
    }

    private RedissonClient safeClient() {
        try {
            return clients.getIfAvailable();
        } catch (Throwable providerFault) {
            diagnose("provider-resolution", null, providerFault);
            return null;
        }
    }

    /** Bounded notice for malformed keys; never contacts Redis, never throws. */
    private void rejectInvalid(String operation, String key) {
        LOG.warn("availability cache {} rejected invalid key [{}]", operation, echoKey(key));
    }

    /**
     * Echo policy: legal (exact-shaped) cache keys may appear in diagnostics,
     * truncated; anything unvalidated — including secret-shaped strings such
     * as connection URIs or credential blobs — collapses to the fixed
     * {@code <invalid>} token so raw input can never reach the log line.
     */
    static String echoKey(String key) {
        if (key == null) {
            return "<none>";
        }
        if (!AvailabilityCacheKey.isExact(key)) {
            return "<invalid>";
        }
        return truncate(key);
    }

    private void diagnose(String operation, String key, Throwable failure) {
        // Message contents are omitted on purpose: vendor messages may embed
        // Redis passwords or JDBC URIs. Class name + fixed tag + gated key only.
        LOG.warn("availability cache {} failed [key={}]: {}", operation,
                echoKey(key), safeClassName(failure));
    }

    /** Message-free, null-safe projection so vendor secrets can never reach the log line. */
    static String safeClassName(Throwable failure) {
        return failure == null ? "<none>" : failure.getClass().getSimpleName();
    }

    private static String truncate(String raw) {
        return raw.length() <= KEY_ECHO_LIMIT ? raw : raw.substring(0, KEY_ECHO_LIMIT);
    }
}
