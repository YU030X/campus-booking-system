package com.yu030x.booking.notification.service;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.notification.entity.NotificationEntity;
import com.yu030x.booking.notification.event.NotificationRequestedEvent;
import com.yu030x.booking.notification.mapper.NotificationMapper;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AFTER_COMMIT consumer for {@link NotificationRequestedEvent}. Delivery runs
 * in one {@code REQUIRES_NEW} transaction that first locks the living
 * recipient row (SELECT ... FOR UPDATE), then performs the null-safe
 * {@code recipient+type+bizId} dedup check, then inserts. No unique key and no
 * migration exist, so the lock serializes two concurrent first deliveries
 * behind the dedup check.
 *
 * <p>Validation is unified here (the event stays a plain carrier):
 * code-point boundaries 100/1000/30, positive userId/bizId, and a secret
 * screen over title/content/type — all rejections are
 * {@link ErrorCode#INVALID_PARAMETER} with zero writes. A missing or deleted
 * recipient is {@link ErrorCode#NOT_FOUND} and likewise never inserts. Missing
 * transaction infrastructure fails closed: delivery aborts instead of falling
 * back to non-transactional persistence. The listener swallows everything,
 * including a null event, without ever dereferencing the event in the catch
 * path, and its diagnostic carries only the failure class name.</p>
 */
public class NotificationDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDelivery.class);
    static final int TITLE_MAX = 100;
    static final int CONTENT_MAX = 1000;
    static final int TYPE_MAX = 30;

    /** Complete JWT shape (three dotted base64url segments, typical eyJ header). */
    private static final Pattern JWT = Pattern.compile(
            "\\bey[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");
    /** Bearer authorization values. */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    /**
     * password/pwd/secret/token/credential/authorization/apiKey/accessKey/jwt/
     * bearer style key=value or key:value assignments.
     */
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|credential|authorization"
                    + "|api[_-]?key|access[_-]?key|jwt|bearer)s?\\s*[:=]\\s*\\S+");
    /** jdbc / redis userinfo credentials (rediss and common postgres schemes included). */
    private static final Pattern URI_USERINFO = Pattern.compile(
            "(?i)\\b(jdbc:[a-z0-9]+|rediss?|mysql|postgresql|postgres)"
                    + "://[^/\\s:@]+:[^@\\s]+@");
    /**
     * Complete mainland mobile number (optionally +86 prefixed). Numeric
     * lookarounds replace {@code \\b} because a {@code \\b} before "+" never
     * holds, which would miss contiguous forms like +8613812345678.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");

    private static final int DIAGNOSTIC_LIMIT = 200;

    private final NotificationMapper mapper;
    private final TransactionOperations requiresNewOperations;

    public NotificationDelivery(NotificationMapper mapper, TransactionOperations requiresNewOperations) {
        this.mapper = mapper;
        this.requiresNewOperations = requiresNewOperations;
    }

    /**
     * After-commit entry point. Fully isolates the application from any
     * delivery failure (including a null event): the catch path logs only the
     * failure class name and never touches the event payload.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationRequested(NotificationRequestedEvent event) {
        try {
            deliver(event);
        } catch (Throwable failure) {
            LOG.warn("notification delivery aborted [failureClass={}]", failureClass(failure));
        }
    }

    /**
     * Throwing direct-delivery entry: every rejection propagates so callers can
     * assert the abort, while all writes stay inside one REQUIRES_NEW unit.
     * Production emission goes exclusively through
     * {@link #onNotificationRequested(NotificationRequestedEvent)}.
     */
    public void deliver(NotificationRequestedEvent event) {
        validate(event);
        if (requiresNewOperations == null) {
            // Fail closed: lock/dedup/insert must never run outside a transaction.
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "notification transaction infrastructure unavailable");
        }
        requiresNewOperations.executeWithoutResult(status -> persist(entityOf(event)));
    }

    /** Single transactional unit: lock recipient, dedup, insert. */
    private void persist(NotificationEntity entity) {
        if (mapper.lockRecipientById(entity.getUserId()) == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "notification recipient missing or deleted");
        }
        if (mapper.countDuplicate(entity.getUserId(), entity.getType(), entity.getBizId()) > 0) {
            return;
        }
        mapper.insert(entity);
    }

    /**
     * Unified producer-input contract; any violation is INVALID_PARAMETER with
     * zero writes and nothing secret ever reaches the persistence layer.
     */
    static void validate(NotificationRequestedEvent event) {
        if (event == null) {
            throw invalid("notification request required");
        }
        requirePositive(event.userId(), "recipient");
        if (event.bizId() != null && event.bizId() <= 0) {
            throw invalid("biz id must be positive when present");
        }
        requireField(event.title(), TITLE_MAX, "title");
        requireField(event.content(), CONTENT_MAX, "content");
        requireField(event.type(), TYPE_MAX, "type");
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + " must be positive");
        }
    }

    private static void requireField(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("notification field must be present [field=" + field + "]");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw invalid("notification field exceeds " + max + " code points [field=" + field + "]");
        }
        if (carriesSecret(value)) {
            throw invalid("notification field contains sensitive data [field=" + field + "]");
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_PARAMETER, message);
    }

    /** Secret screen over user-visible text; detection always rejects the event. */
    static boolean carriesSecret(String text) {
        return JWT.matcher(text).find()
                || BEARER.matcher(text).find()
                || SECRET_KV.matcher(text).find()
                || URI_USERINFO.matcher(text).find()
                || PHONE.matcher(text).find();
    }

    private static NotificationEntity entityOf(NotificationRequestedEvent event) {
        NotificationEntity entity = new NotificationEntity();
        entity.setUserId(event.userId());
        entity.setTitle(event.title());
        entity.setContent(event.content());
        entity.setType(event.type());
        entity.setBizId(event.bizId());
        entity.setIsRead(0);
        return entity;
    }

    /**
     * Bounded diagnostic exposing only the exception class name — never the
     * message, cause chain, or any payload, so secrets cannot leak into logs.
     * A null failure yields the fixed {@code java.lang.Throwable} name.
     */
    public static String failureClass(Throwable failure) {
        String name = failure == null ? "java.lang.Throwable" : failure.getClass().getName();
        return name.length() > DIAGNOSTIC_LIMIT ? name.substring(0, DIAGNOSTIC_LIMIT) : name;
    }
}
