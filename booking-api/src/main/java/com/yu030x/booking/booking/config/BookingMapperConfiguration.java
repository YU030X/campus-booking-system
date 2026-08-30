package com.yu030x.booking.booking.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(
        basePackages = "com.yu030x.booking.booking.mapper",
        lazyInitialization = "true")
public class BookingMapperConfiguration {}
