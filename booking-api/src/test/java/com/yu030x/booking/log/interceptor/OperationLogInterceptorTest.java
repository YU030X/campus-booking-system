package com.yu030x.booking.log.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yu030x.booking.log.annotation.OperationLog;
import com.yu030x.booking.log.context.OperationContextResolver;
import com.yu030x.booking.log.entity.OperationLogEntity;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import com.yu030x.booking.log.writer.OperationLogWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OperationLogInterceptorTest {

    private RecordingWriter writer;
    private OperationLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        OperationLogRedactor redactor = new OperationLogRedactor();
        OperationContextResolver resolver = fixedResolver(42L, "203.0.113.9");
        interceptor = new OperationLogInterceptor(new OperationLogInterceptor.SupplierBundle() {
            @Override
            public OperationLogRedactor redactor() {
                return redactor;
            }

            @Override
            public OperationContextResolver contextResolver() {
                return resolver;
            }

            @Override
            public java.util.function.Supplier<OperationLogWriter> writer() {
                return () -> writer;
            }
        });
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    interface SampleService {
        String greet(String name);

        String login(String username, String password);
    }

    static class SampleServiceImpl implements SampleService {
        int greetCalls;

        @Override
        @OperationLog("booking_create")
        public String greet(String name) {
            greetCalls++;
            return "hi " + name;
        }

        @Override
        @OperationLog("not_in_registry")
        public String login(String username, String password) {
            return "ok";
        }
    }

    static class FailingAnnotatedService {
        @OperationLog("auth_login")
        public void boom(String secretArg) {
            throw new IllegalStateException("phone 13900001111 denied token eyJhbGciOiJIUzI1NiJ9.xxx.yyy");
        }
    }

    static class ChainedCaller {
        private final SampleService proxied;

        ChainedCaller(SampleService proxied) {
            this.proxied = proxied;
        }

        @OperationLog("user_status_update")
        public String outer() {
            return proxied.greet("chain");
        }
    }

    static class RecordingWriter extends OperationLogWriter {
        final List<OperationLogEntity> rows = new ArrayList<>();

        RecordingWriter() {
            super(null, null, new OperationLogRedactor());
        }

        @Override
        public void write(OperationLogEntity entity) {
            rows.add(entity);
        }
    }

    private static OperationContextResolver fixedResolver(Long userId, String ip) {
        return new OperationContextResolver(null) {
            @Override
            public Long currentUserId() {
                return userId;
            }

            @Override
            public String currentIp() {
                return ip;
            }
        };
    }

    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        @SuppressWarnings("unchecked")
        T proxied = (T) factory.getProxy(target.getClass().getClassLoader());
        return proxied;
    }

    @Test
    void approvedCallPersistsSuccessRowWithOutcomeFields() {
        SampleService service = proxy(new SampleServiceImpl());
        assertEquals("hi bob", service.greet("bob"));
        assertEquals(1, writer.rows.size());
        OperationLogEntity row = writer.rows.get(0);
        assertEquals(42L, row.getUserId());
        assertEquals("预约", row.getModule());
        assertEquals("创建预约", row.getOperation());
        assertTrue(row.getMethod().endsWith(".greet"));
        assertEquals("203.0.113.9", row.getIp());
        assertTrue(row.getSuccess());
        assertNull(row.getErrorMsg());
        assertTrue(row.getCostMs() >= 0);
        assertNotNull(row.getCreatedAt());
        assertTrue(row.getParams().contains("\"arg0\":\"bob\""));
    }

    @Test
    void unapprovedActionKeyProducesZeroWritesButNormalResult() {
        SampleService service = proxy(new SampleServiceImpl());
        assertEquals("ok", service.login("u", "p"));
        assertEquals(0, writer.rows.size());
    }

    @Test
    void businessExceptionIsRethrownUnchangedAndFailureRowIsMasked() {
        FailingAnnotatedService service = proxy(new FailingAnnotatedService());
        IllegalStateException thrown = null;
        try {
            service.boom("some-secret");
        } catch (IllegalStateException caught) {
            thrown = caught;
        }
        assertNotNull(thrown);
        assertEquals(1, writer.rows.size());
        OperationLogEntity row = writer.rows.get(0);
        assertFalse(row.getSuccess());
        assertEquals(42L, row.getUserId(), "identity resolved before invocation");
        assertTrue(row.getErrorMsg().contains("IllegalStateException"));
        assertFalse(row.getErrorMsg().contains("13900001111"));
        assertFalse(row.getErrorMsg().contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(row.getParams().contains("\"arg0\":"));
    }

    @Test
    void nestedAnnotatedCallWhileLoggingDoesNotWriteTwice() {
        SampleServiceImpl innerTarget = new SampleServiceImpl();
        SampleService innerProxy = proxy(innerTarget);
        ChainedCaller caller = proxy(new ChainedCaller(innerProxy));
        assertEquals("hi chain", caller.outer());
        assertEquals(1, writer.rows.size(), "inner annotated call must be suppressed during logging");
        assertEquals(1, innerTarget.greetCalls, "business execution still happened exactly once");
    }

    @Test
    void activeTransactionDefersSuccessWriteUntilAfterCompletionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        SampleService service = proxy(new SampleServiceImpl());
        assertEquals("hi now", service.greet("now"));
        assertEquals(0, writer.rows.size(), "no write before transaction completion");
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertEquals(1, writer.rows.size());
        assertTrue(writer.rows.get(0).getSuccess(), "committed outcome must stay success=true");
    }

    @Test
    void successfulMethodRolledBackByOuterTransactionDowngradesToFailureDiagnostic() {
        TransactionSynchronizationManager.initSynchronization();
        SampleService service = proxy(new SampleServiceImpl());
        assertEquals("hi roll", service.greet("roll"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertEquals(1, writer.rows.size());
        OperationLogEntity row = writer.rows.get(0);
        assertFalse(row.getSuccess(), "rollback must force success=false even without business failure");
        assertTrue(row.getErrorMsg() != null && row.getErrorMsg().contains("transaction rolled back"));
        assertTrue(row.getErrorMsg().length() <= 1000);
    }

    @Test
    void activeTransactionRollbackAfterFailurePersistsFailureRowOnce() {
        TransactionSynchronizationManager.initSynchronization();
        FailingAnnotatedService service = proxy(new FailingAnnotatedService());
        try {
            service.boom("v");
        } catch (IllegalStateException ignored) {
            // expected business failure
        }
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertEquals(1, writer.rows.size());
        assertFalse(writer.rows.get(0).getSuccess());
    }
}
