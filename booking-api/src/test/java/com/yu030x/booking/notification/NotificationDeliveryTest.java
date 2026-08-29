package com.yu030x.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.notification.entity.NotificationEntity;
import com.yu030x.booking.notification.event.NotificationRequestedEvent;
import com.yu030x.booking.notification.mapper.NotificationMapper;
import com.yu030x.booking.notification.service.NotificationDelivery;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

class NotificationDeliveryTest {
    private NotificationMapper mapper;
    private NotificationDelivery delivery;
    private final AtomicInteger transactionCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        TransactionOperations requiresNew = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
                transactionCount.incrementAndGet();
                return callback.doInTransaction(null);
            }
        };
        delivery = new NotificationDelivery(mapper, requiresNew);
    }

    private NotificationRequestedEvent event() {
        return new NotificationRequestedEvent(5L, "预约已通过", "内容", "BOOKING_APPROVED", 42L);
    }

    private void assertInvalidParameterZeroWrite(NotificationRequestedEvent payload) {
        org.mockito.Mockito.clearInvocations(mapper);
        assertThatThrownBy(() -> delivery.deliver(payload))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(mapper, never()).lockRecipientById(anyLong());
        verify(mapper, never()).countDuplicate(anyLong(), anyString(), any());
        verify(mapper, never()).insert(any(NotificationEntity.class));
    }

    @Test
    void listenerIsBoundToAfterCommitPhaseOnly() throws NoSuchMethodException {
        TransactionalEventListener listener = NotificationDelivery.class
                .getMethod("onNotificationRequested", NotificationRequestedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void happyPathLocksDedupsThenInsertsExactlyOnceInsideOneRequiresNewUnit() {
        when(mapper.lockRecipientById(5L)).thenReturn(5L);
        when(mapper.countDuplicate(5L, "BOOKING_APPROVED", 42L)).thenReturn(0);

        delivery.deliver(event());

        verify(mapper).lockRecipientById(5L);
        verify(mapper).countDuplicate(5L, "BOOKING_APPROVED", 42L);
        ArgumentCaptor<NotificationEntity> captor =
                ArgumentCaptor.forClass(NotificationEntity.class);
        verify(mapper).insert(captor.capture());
        NotificationEntity entity = captor.getValue();
        assertThat(entity.getUserId()).isEqualTo(5L);
        assertThat(entity.getTitle()).isEqualTo("预约已通过");
        assertThat(entity.getType()).isEqualTo("BOOKING_APPROVED");
        assertThat(entity.getBizId()).isEqualTo(42L);
        assertThat(entity.getIsRead()).isZero();
        assertThat(transactionCount.get()).isEqualTo(1);
    }

    @Test
    void unicodeCodePointBoundariesGovernAllFields() {
        when(mapper.lockRecipientById(5L)).thenReturn(5L);
        when(mapper.countDuplicate(anyLong(), anyString(), any())).thenReturn(0);

        // Astral emoji cost two UTF-16 chars but one code point: these exceed the
        // legacy char counts while staying inside the code-point contract.
        delivery.deliver(new NotificationRequestedEvent(5L,
                "\uD83D\uDE00".repeat(51),          // 102 chars, 51 code points <= 100
                "\uD83D\uDE00".repeat(501),         // 1002 chars, 501 code points <= 1000
                "\uD83D\uDE00".repeat(30),          // 60 chars, 30 code points <= 30
                42L));

        verify(mapper).insert(any(NotificationEntity.class));

        assertInvalidParameterZeroWrite(new NotificationRequestedEvent(5L,
                "x".repeat(101), "c", "TYPE", 1L));
        assertInvalidParameterZeroWrite(new NotificationRequestedEvent(5L,
                "t", "c".repeat(1001), "TYPE", 1L));
        assertInvalidParameterZeroWrite(new NotificationRequestedEvent(5L,
                "t", "c", "T".repeat(31), 1L));
    }

    @Test
    void blankMissingOrNonPositiveBusinessFieldsAreInvalidParameterWithoutAnyWrite() {
        List<NotificationRequestedEvent> cases = List.of(
                new NotificationRequestedEvent(0L, "t", "c", "TYPE", 1L),
                new NotificationRequestedEvent(-7L, "t", "c", "TYPE", 1L),
                new NotificationRequestedEvent(5L, null, "c", "TYPE", 1L),
                new NotificationRequestedEvent(5L, " ", "c", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "t", "c", "TYPE", 0L),
                new NotificationRequestedEvent(5L, "t", "c", "TYPE", -3L));

        for (int index = 0; index < cases.size(); index++) {
            NotificationRequestedEvent payload = cases.get(index);
            assertThatThrownBy(() -> delivery.deliver(payload))
                    .as("case %d", index)
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        }
        verify(mapper, never()).lockRecipientById(anyLong());
        verify(mapper, never()).insert(any(NotificationEntity.class));
        assertThat(transactionCount).hasValue(0);
    }

    @Test
    void secretsJwtsBearerHeadersPhonesAndUserinfoUrisNeverReachPersistence() {
        List<NotificationRequestedEvent> cases = List.of(
                new NotificationRequestedEvent(5L, "token",
                        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c",
                        "TYPE", 1L),
                new NotificationRequestedEvent(5L, "Bearer abc123def456ghi789", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "title", "password=hunter2 请修改", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "title", "secret: s3cr3tValue", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "title", "credential=AKIAExampleKey", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "联系 13812345678 确认", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "+86 13812345678", "内容", "TYPE", 1L),
                // Contiguous prefixed forms must be detected without relying on \b before "+".
                new NotificationRequestedEvent(5L, "+8613812345678", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "8613812345678", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "jdbc:mysql://app:p4ss@db:3306/school", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "redis://default:hunter2@cache:6379", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "rediss://default:hunter2@cache:6379", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "postgres://app:p4ss@db/school", "内容", "TYPE", 1L),
                // Extended secret key coverage.
                new NotificationRequestedEvent(5L, "pwd=abc123456", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "API-KEY: xyz987654321", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "api_key=zyx987654321", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "access-key=s3cr3tKey01", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "title", "authorization: Basic dXNlcjpwYXNz", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "jwt=eyJhbGciOiJIUzI1NiJ9.eyJhIjoxfQ.sig0987654321", "内容", "TYPE", 1L),
                new NotificationRequestedEvent(5L, "title", "内容", "api_token=abc123456", 1L));

        for (int index = 0; index < cases.size(); index++) {
            NotificationRequestedEvent payload = cases.get(index);
            assertThatThrownBy(() -> delivery.deliver(payload))
                    .as("secret case %d must be rejected", index)
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER));
        }
        verify(mapper, never()).lockRecipientById(anyLong());
        verify(mapper, never()).countDuplicate(anyLong(), anyString(), any());
        verify(mapper, never()).insert(any(NotificationEntity.class));
        assertThat(transactionCount).hasValue(0);
    }

    @Test
    void nullEventDirectDeliveryIsInvalidParameterInsteadOfNullPointerException() {
        assertThatThrownBy(() -> delivery.deliver(null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_PARAMETER))
                .isNotInstanceOf(NullPointerException.class);

        verifyNoInteractionsWithMapper();
        assertThat(transactionCount).hasValue(0);
    }

    @Test
    void missingOrDeletedRecipientIsNotFoundAndNeverInserts() {
        when(mapper.lockRecipientById(404L)).thenReturn(null);

        assertThatThrownBy(() -> delivery.deliver(
                new NotificationRequestedEvent(404L, "t", "c", "TYPE", null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode.httpStatus).isEqualTo(404));

        verify(mapper, never()).countDuplicate(anyLong(), anyString(), any());
        verify(mapper, never()).insert(any(NotificationEntity.class));
    }

    @Test
    void duplicateDoesNotInsertAgain() {
        when(mapper.lockRecipientById(5L)).thenReturn(5L);
        when(mapper.countDuplicate(5L, "BOOKING_APPROVED", 42L)).thenReturn(1);

        delivery.deliver(event());

        verify(mapper, never()).insert(any(NotificationEntity.class));
    }

    @Test
    void nullBizIdIsPassedThroughAndOnlyMatchesNullDuplicates() {
        when(mapper.lockRecipientById(5L)).thenReturn(5L);
        when(mapper.countDuplicate(5L, "REMIND", null)).thenReturn(0);

        delivery.deliver(new NotificationRequestedEvent(5L, "提醒", "内容", "REMIND", null));

        verify(mapper).countDuplicate(5L, "REMIND", null);
        ArgumentCaptor<NotificationEntity> captor =
                ArgumentCaptor.forClass(NotificationEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getBizId()).isNull();
    }

    @Test
    void withoutTransactionInfrastructureDeliveryFailsClosedAndWritesNothing() {
        NotificationDelivery bare = new NotificationDelivery(mapper, null);

        assertThatThrownBy(() -> bare.deliver(event()))
                .isInstanceOf(BizException.class);

        verify(mapper, never()).lockRecipientById(anyLong());
        verify(mapper, never()).countDuplicate(anyLong(), anyString(), any());
        verify(mapper, never()).insert(any(NotificationEntity.class));
    }

    @Test
    void nullEventNeverEscapesTheListenerAndTouchNothing() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> delivery.onNotificationRequested(null));
        verifyNoInteractionsWithMapper();
    }

    @Test
    void listenerFullyContainsRuntimeFailuresErrorsAndBrokenDeliveries() {
        class SimulatedLinkage extends LinkageError {
            SimulatedLinkage() { super("simulated"); }
        }
        NotificationDelivery runtimeBoom = brokenDelivery(new IllegalStateException("boom"));
        NotificationDelivery errorBoom = brokenDelivery(new SimulatedLinkage());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> runtimeBoom.onNotificationRequested(event()));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> errorBoom.onNotificationRequested(null));
    }

    @Test
    void diagnosticsExposeOnlyTheFailureClassNameNeverPayloadsOrSecrets() {
        RuntimeException nasty = new RuntimeException(
                "password=hunter2 Bearer eyJhbGciOiJIUzI1NiJ9.a.b jdbc:mysql://app:p4ss@db phone 13812345678");

        String diagnostic = NotificationDelivery.failureClass(nasty);

        assertThat(diagnostic).isEqualTo("java.lang.RuntimeException");
        assertThat(diagnostic)
                .doesNotContain("hunter2")
                .doesNotContain("Bearer")
                .doesNotContain("eyJ")
                .doesNotContain("13812345678")
                .doesNotContain("jdbc");
        assertThat(diagnostic.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void nullFailureYieldsTheFixedThrowableNameWithoutThrowing() {
        assertThat(NotificationDelivery.failureClass(null)).isEqualTo("java.lang.Throwable");
    }

    private NotificationDelivery brokenDelivery(final Throwable thrown) {
        return new NotificationDelivery(mapper, null) {
            @Override
            public void deliver(NotificationRequestedEvent event) {
                if (thrown instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw new AssertionError("test requires an unchecked failure", thrown);
            }
        };
    }

    private void verifyNoInteractionsWithMapper() {
        verify(mapper, never()).lockRecipientById(anyLong());
        verify(mapper, never()).countDuplicate(anyLong(), anyString(), any());
        verify(mapper, never()).insert(any(NotificationEntity.class));
    }
}
