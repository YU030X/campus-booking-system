package com.yu030x.booking.log.interceptor;

import com.yu030x.booking.log.annotation.OperationLog;
import com.yu030x.booking.log.context.OperationContextResolver;
import com.yu030x.booking.log.entity.OperationLogEntity;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import com.yu030x.booking.log.registry.OperationAction;
import com.yu030x.booking.log.writer.OperationLogWriter;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Captures annotated invocations outcome/cost/IP/user and hands a bounded,
 * sanitized row to the writer. Unannotated methods or unapproved action keys
 * pass through untouched with zero writes. Persistence is deferred to
 * afterCompletion while a business transaction is active so committed calls
 * are logged as successful only once the surrounding transaction committed;
 * failures keep the final success flag false. Log problems can never alter the
 * business return value or thrown exception.
 */
public class OperationLogInterceptor implements MethodInterceptor {

    private static final ThreadLocal<Boolean> IN_LOGGING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SupplierBundle suppliers;
    private final ConcurrentHashMap<MethodCacheKey, OperationAction> actions = new ConcurrentHashMap<>();

    public OperationLogInterceptor(SupplierBundle suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (Boolean.TRUE.equals(IN_LOGGING.get())) {
            return invocation.proceed();
        }
        Method mostSpecific = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass(invocation));
        OperationAction action = resolveAction(mostSpecific);
        if (action == null) {
            return invocation.proceed();
        }
        Long userId = resolveUserId();
        long startNanos = System.nanoTime();
        boolean failed = false;
        Throwable error = null;
        IN_LOGGING.set(Boolean.TRUE);
        try {
            return invocation.proceed();
        } catch (Throwable businessFailure) {
            failed = true;
            error = businessFailure;
            throw businessFailure;
        } finally {
            IN_LOGGING.remove();
            persistOutcome(invocation, mostSpecific, action, userId, startNanos, failed, error);
        }
    }

    private void persistOutcome(MethodInvocation invocation, Method method, OperationAction action,
                                Long userId, long startNanos, boolean failed, Throwable error) {
        try {
            OperationLogEntity entity = new OperationLogEntity();
            entity.setUserId(userId);
            entity.setModule(action.module());
            entity.setOperation(action.operation());
            String methodText = method.getDeclaringClass().getName() + "." + method.getName();
            entity.setMethod(OperationLogRedactor.fits(methodText, 200));
            entity.setParams(suppliers.redactor().project(invocation.getArguments()));
            entity.setIp(OperationLogRedactor.fits(resolveIp(), 50));
            entity.setCostMs((System.nanoTime() - startNanos) / 1_000_000L);
            entity.setSuccess(!failed);
            entity.setErrorMsg(failed ? suppliers.redactor().error(error) : null);
            entity.setCreatedAt(LocalDateTime.now());
            OperationLogWriter writer = suppliers.writer().get();
            if (writer == null) {
                return;
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        // Reconcile with the definitive outcome: only an unfailed
                        // invocation whose surrounding transaction committed is
                        // reported successful; rollback/unknown outcomes become
                        // failures with a bounded diagnostic when no cause exists.
                        boolean committed = status == TransactionSynchronization.STATUS_COMMITTED;
                        if (!failed && !committed) {
                            entity.setSuccess(Boolean.FALSE);
                            if (entity.getErrorMsg() == null || entity.getErrorMsg().isBlank()) {
                                entity.setErrorMsg(
                                        OperationLogRedactor.fits("transaction rolled back", 1000));
                            }
                        }
                        writer.write(entity);
                    }
                });
            } else {
                writer.write(entity);
            }
        } catch (Throwable loggingFailure) {
            // Absolute isolation: even assembly of the log row must not affect business flow.
        }
    }

    private OperationAction resolveAction(Method method) {
        OperationAnnotation annotation = findAnnotation(method);
        if (annotation == null) {
            return null;
        }
        return actions.computeIfAbsent(new MethodCacheKey(annotation.declaredOn(), annotation.method()),
                key -> OperationAction.byKey(annotation.action()));
    }

    private OperationAnnotation findAnnotation(Method method) {
        for (Class<?> type : classAndInterfaces(method.getDeclaringClass())) {
            OperationLog direct = method.getAnnotation(OperationLog.class);
            if (direct != null) {
                return new OperationAnnotation(type, method, direct.value());
            }
            try {
                Method candidate = type.getMethod(method.getName(), method.getParameterTypes());
                OperationLog found = candidate.getAnnotation(OperationLog.class);
                if (found != null) {
                    return new OperationAnnotation(type, candidate, found.value());
                }
            } catch (NoSuchMethodException ignored) {
                // continue searching upward
            }
        }
        return null;
    }

    private Class<?>[] classAndInterfaces(Class<?> type) {
        java.util.Set<Class<?>> types = new java.util.LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            types.add(current);
            for (Class<?> iface : current.getInterfaces()) {
                types.add(iface);
            }
        }
        return types.toArray(Class<?>[]::new);
    }

    private Class<?> targetClass(MethodInvocation invocation) {
        return invocation.getThis() != null ? invocation.getThis().getClass() : invocation.getMethod().getDeclaringClass();
    }

    private Long resolveUserId() {
        try {
            return suppliers.contextResolver().currentUserId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveIp() {
        try {
            return suppliers.contextResolver().currentIp();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record MethodCacheKey(Class<?> declaredOn, Method method) {
    }

    private record OperationAnnotation(Class<?> declaredOn, Method method, String action) {
    }

    /** Deferred lookups so eager proxy wiring never forces bean instantiation. */
    public interface SupplierBundle {
        OperationLogRedactor redactor();

        OperationContextResolver contextResolver();

        java.util.function.Supplier<OperationLogWriter> writer();
    }
}
