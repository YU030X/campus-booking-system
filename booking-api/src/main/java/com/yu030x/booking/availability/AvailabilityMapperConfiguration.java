package com.yu030x.booking.availability;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = BookingSlotMapper.class, lazyInitialization = "true")
public class AvailabilityMapperConfiguration {}
