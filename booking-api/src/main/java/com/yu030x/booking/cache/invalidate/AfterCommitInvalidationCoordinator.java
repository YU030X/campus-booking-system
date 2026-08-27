package com.yu030x.booking.cache.invalidate;

import com.yu030x.booking.cache.key.AvailabilityCacheKey;
import com.yu030x.booking.cache.port.AvailabilityCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * After-commit invalidation coordinator. While a transaction synchronization
 * is active the deletion is registered and executed only in afterCommit; a
 * rollback therefore removes nothing (stale cache is preferable to evicting on
 * failed mutations). Outside any transaction the removal executes immediately.
 *
 * <p>The coordinator keeps its "never throws" promise under every path: null
 * requests, failed synchronization registration and caches whose invalidate
 * itself throws are all contained locally with bounded diagnostics. If
 * registration fails there is deliberate no immediate fallback deletion, since
 * firing a delete outside the container-managed lifecycle could remove a key
 * even though the surrounding transaction might still roll back.</p>
 *
 * <p>Diagnostics never echo an unvalidated raw key: requests are gated by
 * {@link AvailabilityInvalidationRequest} (which demands
 * {@link AvailabilityCacheKey#isExact}), and every log projection funnels
 * through {@link #echoKey}.</p>
 */
public class AfterCommitInvalidationCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(AfterCommitInvalidationCoordinator.class);

    private final AvailabilityCachePort cache;

    public AfterCommitInvalidationCoordinator(AvailabilityCachePort cache) {
        this.cache = cache;
    }

    /** Never throws regardless of request shape, tx state, port faults or registration failures. */
    public void scheduleAfterCommit(AvailabilityInvalidationRequest request) {
        try {
            if (request == null) {
                LOG.warn("availability invalidation skipped: missing request");
                return;
            }
            // request.key() is pre-validated by the request contract; echo via the
            // gated projection keeps that guarantee explicit even on refactors.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            cache.invalidate(request.key());
                        } catch (Throwable delegatedButContained) {
                            LOG.warn("availability invalidation afterCommit suppressed [key={}] : {}",
                                    echoKey(request.key()), thrown(delegatedButContained));
                        }
                    }
                });
            } else {
                try {
                    cache.invalidate(request.key());
                } catch (Throwable delegatedButContained) {
                    LOG.warn("availability invalidation immediate suppressed [key={}] : {}",
                            echoKey(request.key()), thrown(delegatedButContained));
                }
            }
        } catch (Throwable schedulingOrRegistrationFault) {
            // Includes registerSynchronization failures; stay silent-safe without
            // falling back to a delete that a rollback could not undo.
            LOG.warn("availability invalidation scheduling suppressed [key={}]: {}",
                    echoKey(request == null ? null : request.key()),
                    thrown(schedulingOrRegistrationFault));
        }
    }

    /** Gated echo: exact keys truncated, everything unverifiable becomes {@code <invalid>}. */
    static String echoKey(String key) {
        if (key == null) {
            return "<none>";
        }
        return AvailabilityCacheKey.isExact(key) ? key : "<invalid>";
    }

    private static String thrown(Throwable failure) {
        return failure == null ? "<none>" : failure.getClass().getSimpleName();
    }
}
