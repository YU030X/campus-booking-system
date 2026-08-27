package com.yu030x.booking.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Manually owns the opt-in Redis graph. Boot Redis auto-configuration is excluded in
 * application.yml so disabled mode cannot create a localhost connection factory.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperties.class)
@ConditionalOnProperty(prefix = "booking.redis", name = "enabled", havingValue = "true")
public class RedisFoundationConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisClientFactory.class)
    RedisClientFactory redisClientFactory() {
        return properties -> Redisson.create(createRedissonConfig(properties));
    }

    Config createRedissonConfig(RedisProperties properties) {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        var server = config.useSingleServer()
                .setAddress(redissonAddress(properties))
                .setDatabase(properties.getDatabase())
                .setConnectTimeout(properties.getConnectTimeoutMs())
                .setTimeout(properties.getCommandTimeoutMs());
        if (properties.getPassword() != null) {
            config.setPassword(properties.getPassword());
        }
        // Do not set a lease time or override Redisson's 30-second watchdog default.
        return config;
    }

    String redissonAddress(RedisProperties properties) {
        return "redis://" + properties.getHost() + ":" + properties.getPort();
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedisProperties properties, RedisClientFactory factory) {
        properties.validateEnabledConfiguration();
        return factory.create(properties);
    }

    @Bean(destroyMethod = "destroy")
    LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        properties.validateEnabledConfiguration();
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if (properties.getPassword() != null) {
            standalone.setPassword(properties.getPassword());
        }
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .build();
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(properties.getCommandTimeoutMs()))
                .clientOptions(clientOptions)
                .build();
        return new LettuceConnectionFactory(standalone, clientConfiguration);
    }

    @Bean
    RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setDefaultSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
