package com.yu030x.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.notification.config.NotificationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The notification slice ships behind booking.notifications.enabled with
 * matchIfMissing=false: cutting the property (or setting it false) must leave
 * no beans at all, independently of the other optional slices.
 */
class NotificationConfigurationActivationTest {

    @Test
    void switchIsOptInAndDefaultsToDisabled() {
        ConditionalOnProperty condition = NotificationConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("booking.notifications.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void deliveryBeanDirectlyRequiresATransactionManagerSoDeliveryNeverDegrades() throws Exception {
        Class<?>[] parameters = NotificationConfiguration.class
                .getMethod("notificationDelivery",
                        com.yu030x.booking.notification.mapper.NotificationMapper.class,
                        org.springframework.transaction.PlatformTransactionManager.class)
                .getParameterTypes();
        assertThat(parameters).hasSize(2);
        assertThat(parameters[1]).isEqualTo(org.springframework.transaction.PlatformTransactionManager.class);

        // Propagation must be hard-wired REQUIRES_NEW for every delivery.
        assertThat(org.springframework.transaction.support.TransactionTemplate.PROPAGATION_REQUIRES_NEW)
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
