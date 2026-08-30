package com.yu030x.booking.log.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yu030x.booking.log.annotation.OperationLog;
import com.yu030x.booking.log.context.OperationContextResolver;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import com.yu030x.booking.log.interceptor.OperationLogInterceptor;
import com.yu030x.booking.log.writer.OperationLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;

class OperationLogWiringPostProcessorTest {

    private OperationLogWiringPostProcessor processor;
    private RecordingWriter writer;

    private static final class RecordingWriter extends OperationLogWriter {
        int writes;

        private RecordingWriter() {
            super(null, null, new com.yu030x.booking.log.redact.OperationLogRedactor());
        }

        @Override
        public void write(com.yu030x.booking.log.entity.OperationLogEntity entity) {
            writes++;
        }
    }

    interface PlainRepo {
        String find(String id);
    }

    static class PlainRepoImpl implements PlainRepo {
        @Override
        public String find(String id) {
            return id;
        }
    }

    static class AnnotatedService {
        @OperationLog("resource_update")
        public String update(String name) {
            return name.toUpperCase();
        }

        public String untouched() {
            return "raw";
        }
    }

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        OperationLogInterceptor interceptor = new OperationLogInterceptor(
                new OperationLogInterceptor.SupplierBundle() {
                    @Override
                    public OperationLogRedactor redactor() {
                        return new OperationLogRedactor();
                    }

                    @Override
                    public OperationContextResolver contextResolver() {
                        return new OperationContextResolver(null);
                    }

                    @Override
                    public java.util.function.Supplier<OperationLogWriter> writer() {
                        return () -> writer;
                    }
                });
        processor = new OperationLogWiringPostProcessor(interceptor);
    }

    @Test
    void beanWithoutAnnotatedMethodIsReturnedUnwrapped() {
        PlainRepoImpl original = new PlainRepoImpl();
        assertSame(original, processor.postProcessAfterInitialization(original, "plainRepo"));
    }

    @Test
    void annotatedBeanIsProxiedOnceAndApprovedInvocationWrites() {
        Object out = processor.postProcessAfterInitialization(new AnnotatedService(), "svc");
        assertNotSame(AnnotatedService.class, out.getClass());
        assertTrue(out instanceof Advised advised
                        && java.util.Arrays.stream(advised.getAdvisors())
                        .anyMatch(a -> a.getAdvice() instanceof OperationLogInterceptor),
                "proxy must carry the operation-log advice exactly once");
        // Idempotent second pass must not duplicate the advice.
        Object again = processor.postProcessAfterInitialization(out, "svc");
        Advised advisedAgain = (Advised) again;
        long adviceCount = java.util.Arrays.stream(advisedAgain.getAdvisors())
                .filter(a -> a.getAdvice() instanceof OperationLogInterceptor).count();
        assertTrue(adviceCount == 1, () -> "expected single advice but got " + adviceCount);

        AnnotatedService proxied = (AnnotatedService) out;
        assertEquals1(proxied.update("abc"));
        org.junit.jupiter.api.Assertions.assertEquals(1, writer.writes,
                "approved annotated method should persist one bounded row");
    }

    @Test
    void unannotatedMethodsOnWrappedBeanStaySilent() {
        AnnotatedService proxied = (AnnotatedService) processor.postProcessAfterInitialization(
                new AnnotatedService(), "svc2");
        assertEquals1(proxied.untouched());
        org.junit.jupiter.api.Assertions.assertEquals(0, writer.writes);
    }

    private static void assertEquals1(String value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
        assertFalse(value.isEmpty());
    }
}
