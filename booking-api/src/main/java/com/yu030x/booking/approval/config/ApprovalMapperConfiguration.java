package com.yu030x.booking.approval.config;

import com.yu030x.booking.approval.mapper.ApprovalRecordMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(
        basePackageClasses = ApprovalRecordMapper.class,
        annotationClass = Mapper.class,
        lazyInitialization = "true")
public class ApprovalMapperConfiguration {
}
