package com.yu030x.booking.cache.invalidate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yu030x.booking.cache.port.AvailabilityCachePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitInvalidationCoordinatorTest {

    private static final String KEY = "resource:available-slots:42:2026-08-27";

    private AvailabilityCachePort port;
    private AfterCommitInvalidationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        port = mock(AvailabilityCachePort.class);
        coordinator = new AfterCommitInvalidationCoordinator(port);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void withoutTransactionTheDeletionRunsImmediately() {
        coordinator.scheduleAfterCommit(new AvailabilityInvalidationRequest(KEY, "availability"));
        verify(port).invalidate(KEY);
    }

    @Test
    void activeTransactionDefersUntilAfterCommitOnlyThenDeletes() {
        TransactionSynchronizationManager.initSynchronization();
        coordinator.scheduleAfterCommit(new AvailabilityInvalidationRequest(KEY, "resource"));
        verify(port, never()).invalidate(KEY); // nothing removed before commit

        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        org.junit.jupiter.api.Assertions.assertEquals(1, synchronizations.size());
        for (TransactionSynchronization sync : synchronizations) {
            sync.afterCommit();
        }
        verify(port).invalidate(KEY);
    }

    @Test
    void rolledBackTransactionLeavesTheKeyIntact() {
        TransactionSynchronizationManager.initSynchronization();
        coordinator.scheduleAfterCommit(new AvailabilityInvalidationRequest(KEY, "booking-cancel"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(port, never()).invalidate(KEY);
        // afterCompletion deliberately does not delete; stale cache beats wrong eviction.
    }

    @Test
    void nullRequestIsContainedSilentlyWithoutTouchingThePort() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> coordinator.scheduleAfterCommit(null));
        // The request type itself refuses anything that is not an exact key at
        // construction — including secret-shaped strings that must never reach logs.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityInvalidationRequest(" ", "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityInvalidationRequest(null, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityInvalidationRequest(
                        "redis://default:p4ssw0rd@host", "cred-shaped"),
                () -> "secret-shaped key must be rejected before any logging path exists");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AvailabilityInvalidationRequest("booking:lock:1:2026-01-01", "foreign"));
        verify(port, never()).invalidate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void coordinatorEchoGateNeverReturnsUnvalidatedRawKeys() {
        org.junit.jupiter.api.Assertions.assertEquals("<none>",
                AfterCommitInvalidationCoordinator.echoKey(null));
        org.junit.jupiter.api.Assertions.assertEquals("<invalid>",
                AfterCommitInvalidationCoordinator.echoKey("password=s3cret"),
                () -> "secret-shaped input collapses to <invalid>");
        org.junit.jupiter.api.Assertions.assertEquals("<invalid>",
                AfterCommitInvalidationCoordinator.echoKey(""));
        org.junit.jupiter.api.Assertions.assertEquals(KEY,
                AfterCommitInvalidationCoordinator.echoKey(KEY),
                () -> "exact keys remain echoable");
    }

    @Test
    void portInvalidateThrowingNeverEscapesImmediatePath() {
        org.mockito.Mockito.doThrow(new IllegalStateException("port boom"))
                .when(port).invalidate(KEY);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                coordinator.scheduleAfterCommit(new AvailabilityInvalidationRequest(KEY, "availability")));
    }

    @Test
    void portInvalidateThrowingInsideAfterCommitNeverEscapes() {
        org.mockito.Mockito.doThrow(new RuntimeException("commit hook boom"))
                .when(port).invalidate(KEY);
        TransactionSynchronizationManager.initSynchronization();
        coordinator.scheduleAfterCommit(new AvailabilityInvalidationRequest(KEY, "resource"));
        for (TransactionSynchronization sync :
                TransactionSynchronizationManager.getSynchronizations()) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(sync::afterCommit,
                    "container would surface callback exceptions; they must be contained");
        }
        verify(port).invalidate(KEY);
    }
}
