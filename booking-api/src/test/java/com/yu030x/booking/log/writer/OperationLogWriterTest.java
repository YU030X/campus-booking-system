package com.yu030x.booking.log.writer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yu030x.booking.log.entity.OperationLogEntity;
import com.yu030x.booking.log.mapper.OperationLogMapper;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationLogWriterTest {

    @Test
    void directInsertWithoutTransactionInfrastructureStillPersists() {
        RecordingMapper mapper = new RecordingMapper();
        new OperationLogWriter(mapper, null, new OperationLogRedactor()).write(sample());
        assertEquals(1, mapper.inserts.size());
    }

    @Test
    void mapperFailureIsSwallowedWithNoBusinessImpactAndZeroThrow() {
        OperationLogMapper broken = entity -> {
            throw new RuntimeException("db down");
        };
        OperationLogWriter writer = new OperationLogWriter(broken, null, new OperationLogRedactor());
        assertDoesNotThrow(() -> writer.write(sample()));
    }

    @Test
    void requiresNewOperationsRunsExactlyOncePerWriteAndFailuresStayIsolated() {
        List<OperationLogEntity> committed = new ArrayList<>();
        OperationLogMapper mapper = entity -> {
            if ("fail".equals(entity.getModule())) {
                throw new RuntimeException("constraint");
            }
            committed.add(entity);
            return 1;
        };
        org.springframework.transaction.support.TransactionOperations requiresNew =
                new org.springframework.transaction.support.TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
                return callback.doInTransaction(null);
            }
        };
        OperationLogWriter writer = new OperationLogWriter(mapper, requiresNew, new OperationLogRedactor());
        writer.write(sample());
        assertEquals(1, committed.size());
        OperationLogEntity failing = sample();
        failing.setModule("fail");
        assertDoesNotThrow(() -> writer.write(failing));
        assertEquals(1, committed.size());
    }

    @Test
    void diagnosticIsBoundedAndNeverLeaksInlineOrUriSecrets() {
        OperationLogWriter writer =
                new OperationLogWriter(new RecordingMapper(), null, new OperationLogRedactor());
        RuntimeException boom = new RuntimeException(
                "password=sup3r-pass apiKey: s3cr3tKey jdbc:mysql://app:p4ssw0rd@db:3306/x");
        String diagnostic = writer.diagnostic(boom);
        assertTrue(diagnostic.length() <= 200, () -> "bounded diagnostic, was " + diagnostic.length());
        assertFalse(diagnostic.contains("sup3r-pass"));
        assertFalse(diagnostic.contains("s3cr3tKey"));
        assertFalse(diagnostic.contains("p4ssw0rd"));
        assertTrue(diagnostic.startsWith("RuntimeException"));
    }

    private OperationLogEntity sample() {
        OperationLogEntity entity = new OperationLogEntity();
        entity.setModule("预约");
        entity.setOperation("创建预约");
        return entity;
    }

    static class RecordingMapper implements OperationLogMapper {
        final List<OperationLogEntity> inserts = new ArrayList<>();

        @Override
        public int insert(OperationLogEntity entity) {
            inserts.add(entity);
            return 1;
        }
    }
}
