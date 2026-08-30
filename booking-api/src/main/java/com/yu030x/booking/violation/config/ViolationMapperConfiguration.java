package com.yu030x.booking.violation.config;

import com.yu030x.booking.violation.mapper.ViolationRecordMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(
        basePackageClasses = ViolationRecordMapper.class,
        annotationClass = Mapper.class,
        lazyInitialization = "true")
public class ViolationMapperConfiguration {
}
