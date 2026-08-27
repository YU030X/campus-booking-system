package com.yu030x.booking.log.writer;

import com.yu030x.booking.log.entity.OperationLogEntity;
import com.yu030x.booking.log.mapper.OperationLogMapper;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Best-effort, failure-isolated persistence of operation-log rows. The log
 * write runs in its own {@code REQUIRES_NEW} transaction when transaction
 * infrastructure is available; any failure is swallowed with a bounded,
 * sanitized diagnostic and never alters the primary response or throws.
 */
public class OperationLogWriter {

    private static final Logger LOG = LoggerFactory.getLogger(OperationLogWriter.class);
    private static final int DIAGNOSTIC_LIMIT = 200;

    private final OperationLogMapper mapper;
    private final TransactionOperations requiresNewOperations;
    private final OperationLogRedactor redactor;

    public OperationLogWriter(OperationLogMapper mapper, TransactionOperations requiresNewOperations,
                              OperationLogRedactor redactor) {
        this.mapper = mapper;
        this.requiresNewOperations = requiresNewOperations;
        this.redactor = redactor != null ? redactor : new OperationLogRedactor();
    }

    /** Never throws; failures are reduced to a bounded, secret-free diagnostic line. */
    public void write(OperationLogEntity entity) {
        try {
            if (requiresNewOperations != null) {
                requiresNewOperations.executeWithoutResult(status -> mapper.insert(entity));
            } else {
                mapper.insert(entity);
            }
        } catch (Throwable failure) {
            LOG.warn("operation_log write failed [module={}, operation={}]: {}",
                    entity.getModule(), entity.getOperation(), diagnostic(failure));
        }
    }

    /** Bounded (<={@value DIAGNOSTIC_LIMIT} chars) masked projection of the failure. */
    String diagnostic(Throwable failure) {
        String text = null;
        try {
            text = redactor.error(failure);
        } catch (Throwable neverEscape) {
            // Sanitizing must not create its own leak path.
        }
        if (text == null || text.isBlank()) {
            text = String.valueOf(failure.getClass().getSimpleName());
        }
        return OperationLogRedactor.fits(text, DIAGNOSTIC_LIMIT);
    }
}
