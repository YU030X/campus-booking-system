package com.yu030x.booking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.cache.config.AvailabilityCacheConfiguration;
import com.yu030x.booking.log.config.OperationLogConfiguration;
import com.yu030x.booking.notification.config.NotificationConfiguration;
import com.yu030x.booking.notification.controller.NotificationController;
import com.yu030x.booking.statistics.config.StatisticsConfiguration;
import com.yu030x.booking.statistics.controller.AdminStatisticsController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class SupportingCapabilitiesActivationTest {

    @Test
    void eachOptionalCapabilityUsesItsOwnOptInOnlyProperty() {
        Map<Class<?>, String> conditions = new LinkedHashMap<>();
        conditions.put(OperationLogConfiguration.class, "booking.operation-log.enabled");
        conditions.put(AvailabilityCacheConfiguration.class, "booking.cache.enabled");
        conditions.put(NotificationConfiguration.class, "booking.notifications.enabled");
        conditions.put(NotificationController.class, "booking.notifications.enabled");
        conditions.put(StatisticsConfiguration.class, "booking.statistics.enabled");
        conditions.put(AdminStatisticsController.class, "booking.statistics.enabled");

        conditions.forEach(SupportingCapabilitiesActivationTest::assertOptInOnly);
        assertThat(conditions.values()).contains(
                "booking.operation-log.enabled",
                "booking.cache.enabled",
                "booking.notifications.enabled",
                "booking.statistics.enabled");
    }

    private static void assertOptInOnly(Class<?> type, String property) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as(type.getName()).isNotNull();
        assertThat(condition.name()).as(type.getName()).containsExactly(property);
        assertThat(condition.havingValue()).as(type.getName()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).as(type.getName()).isFalse();
    }
}
