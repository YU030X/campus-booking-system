package com.yu030x.booking.resource.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(
        basePackages = "com.yu030x.booking.resource.mapper",
        lazyInitialization = "true")
public class ResourceMapperConfiguration {}
