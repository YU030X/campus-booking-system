package com.yu030x.booking.common.config;

import org.redisson.api.RedissonClient;

@FunctionalInterface
public interface RedisClientFactory {
    RedissonClient create(RedisProperties properties);
}
