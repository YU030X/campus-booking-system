package com.yu030x.booking.notification.config;

import com.yu030x.booking.notification.mapper.NotificationMapper;
import com.yu030x.booking.notification.service.NotificationDelivery;
import com.yu030x.booking.notification.service.NotificationService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent switch for the notification slice
 * ({@code booking.notifications.enabled}, default false). No shared
 * configuration files, pom, sql, or owner packages are modified. Delivery
 * unconditionally demands a {@link PlatformTransactionManager} and always runs
 * REQUIRES_NEW: without transaction infrastructure the slice cannot satisfy its
 * lock-then-dedup-then-insert contract, so it fails closed instead of degrading
 * to non-transactional persistence.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true", matchIfMissing = false)
@MapperScan(basePackages = "com.yu030x.booking.notification.mapper", lazyInitialization = "true")
public class NotificationConfiguration {

    @Bean
    public NotificationService notificationService(NotificationMapper mapper) {
        return new NotificationService(mapper);
    }

    @Bean
    public NotificationDelivery notificationDelivery(NotificationMapper mapper,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return new NotificationDelivery(mapper, template);
    }
}
