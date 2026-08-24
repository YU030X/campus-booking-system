package com.yu030x.booking.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest(
        classes = BookingApplication.class,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.mybatis.spring.boot.autoconfigure.MybatisPlusAutoConfiguration",
            "booking.security.jwt-secret=",
            "booking.identity.enabled=false",
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false"
        })
class AvailabilityContextIntegrationTest {
    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void availabilityBeansLoadWithoutMapperInstantiation() {
        assertThat(context.getBean(AvailabilityService.class)).isNotNull();
        assertThat(context.getBean(AvailabilityController.class)).isNotNull();
        assertThat(context.getBeanFactory().containsBeanDefinition("bookingSlotMapper")).isTrue();
        assertThat(context.getBeanFactory().getBeanDefinition("bookingSlotMapper").isLazyInit()).isTrue();
    }
}
