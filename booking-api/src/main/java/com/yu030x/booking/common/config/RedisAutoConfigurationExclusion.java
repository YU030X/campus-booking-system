package com.yu030x.booking.common.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps Redis auto-configuration excluded even when a test or deployment replaces the
 * spring.autoconfigure.exclude property instead of extending the YAML list.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
public class RedisAutoConfigurationExclusion {
}
