package com.yu030x.booking.log.config;

import com.yu030x.booking.auth.security.BookingPrincipalAccessor;
import com.yu030x.booking.log.context.OperationContextResolver;
import com.yu030x.booking.log.interceptor.OperationLogInterceptor;
import com.yu030x.booking.log.redact.OperationLogRedactor;
import com.yu030x.booking.log.wiring.OperationLogWiringPostProcessor;
import com.yu030x.booking.log.writer.OperationLogWriter;
import java.util.function.Supplier;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent switch for the operation-log slice
 * ({@code booking.operation-log.enabled}, default false). No shared
 * configuration files are modified by this slice.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.operation-log.enabled", havingValue = "true", matchIfMissing = false)
@MapperScan(basePackages = "com.yu030x.booking.log.mapper", lazyInitialization = "true")
public class OperationLogConfiguration {

    @Bean
    public OperationLogRedactor operationLogRedactor() {
        return new OperationLogRedactor();
    }

    @Bean
    public OperationContextResolver operationContextResolver(
            ObjectProvider<BookingPrincipalAccessor> principalAccessors) {
        return new OperationContextResolver(principalAccessors);
    }

    @Bean
    public OperationLogWriter operationLogWriter(
            com.yu030x.booking.log.mapper.OperationLogMapper mapper,
            ObjectProvider<PlatformTransactionManager> transactionManagers,
            OperationLogRedactor redactor) {
        PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable();
        if (transactionManager == null) {
            return new OperationLogWriter(mapper, null, redactor);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return new OperationLogWriter(mapper, template, redactor);
    }

    @Bean
    public OperationLogInterceptor operationLogInterceptor(OperationLogRedactor redactor,
                                                           OperationContextResolver contextResolver,
                                                           final ObjectProvider<OperationLogWriter> writers) {
        return new OperationLogInterceptor(new OperationLogInterceptor.SupplierBundle() {
            @Override
            public OperationLogRedactor redactor() {
                return redactor;
            }

            @Override
            public OperationContextResolver contextResolver() {
                return contextResolver;
            }

            @Override
            public Supplier<OperationLogWriter> writer() {
                return writers::getIfAvailable;
            }
        });
    }

    @Bean
    public static OperationLogWiringPostProcessor operationLogWiringPostProcessor(
            OperationLogInterceptor interceptor) {
        return new OperationLogWiringPostProcessor(interceptor);
    }
}
