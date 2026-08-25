package com.yu030x.booking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.redisson.api.RedissonClient;

class RedisFoundationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RedisFoundationConfiguration.class);

    @Test
    void disabledModeHasNoRedisClientsAndHealthCanInitialize() {
        runner.withConfiguration(AutoConfigurations.of(
                        HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(org.springframework.boot.actuate.health.HealthEndpoint.class);
                    assertThat(context).doesNotHaveBean(RedissonClient.class);
                    assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
                    assertThat(context).doesNotHaveBean(ReactiveRedisConnectionFactory.class);
                    context.getBean(org.springframework.boot.actuate.health.HealthEndpoint.class).health();
                });
    }

    @Test
    void validEnabledModeCreatesExactlyOneSharedFoundationAndClosesRedisson() {
        RedissonClient client = mock(RedissonClient.class);
        RedisClientFactory factory = properties -> client;

        runner.withPropertyValues(
                        "booking.redis.enabled=true",
                        "booking.redis.host=redis.internal",
                        "booking.redis.port=6380",
                        "booking.redis.password=secret-value")
                .withBean(RedisClientFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedissonClient.class);
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(RedisTemplate.class);
                    RedisProperties properties = context.getBean(RedisProperties.class);
                    assertThat(properties.getPassword()).isEqualTo("secret-value");
                    assertThat(properties.getDatabase()).isZero();
                });

        verify(client).shutdown();
    }

    @Test
    void defaultTimeoutsAreBoundAsTypedMilliseconds() {
        RedissonClient client = mock(RedissonClient.class);
        runner.withPropertyValues("booking.redis.enabled=true", "booking.redis.host=redis.internal")
                .withBean(RedisClientFactory.class, () -> properties -> {
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(3000);
                    assertThat(properties.getCommandTimeoutMs()).isEqualTo(5000);
                    return client;
                })
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @MethodSource("validTimeoutBoundaries")
    void acceptsInclusiveTimeoutBoundaries(String connectTimeout, String commandTimeout) {
        RedissonClient client = mock(RedissonClient.class);
        runner.withPropertyValues(
                        "booking.redis.enabled=true",
                        "booking.redis.host=redis.internal",
                        "booking.redis.connect-timeout-ms=" + connectTimeout,
                        "booking.redis.command-timeout-ms=" + commandTimeout)
                .withBean(RedisClientFactory.class, () -> properties -> client)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static Stream<Arguments> validTimeoutBoundaries() {
        return Stream.of(
                Arguments.of("100", "100"),
                Arguments.of("10000", "30000"));
    }

    @ParameterizedTest
    @MethodSource("invalidProperties")
    void invalidEnabledConfigurationFailsBeforeClientConstruction(String property, String value) {
        RedissonClient client = mock(RedissonClient.class);
        AtomicBoolean factoryCalled = new AtomicBoolean();
        runner.withPropertyValues(
                        "booking.redis.enabled=true",
                        "booking.redis.host=redis.internal",
                        property + "=" + value)
                .withBean(RedisClientFactory.class, () -> properties -> {
                    factoryCalled.set(true);
                    return client;
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("booking.redis");
                });
        assertThat(factoryCalled).isFalse();
    }

    private static Stream<Arguments> invalidProperties() {
        return Stream.of(
                Arguments.of("booking.redis.host", " "),
                Arguments.of("booking.redis.port", "not-a-number"),
                Arguments.of("booking.redis.port", "0"),
                Arguments.of("booking.redis.port", "65536"),
                Arguments.of("booking.redis.connect-timeout-ms", "NaN"),
                Arguments.of("booking.redis.connect-timeout-ms", "99"),
                Arguments.of("booking.redis.connect-timeout-ms", "10001"),
                Arguments.of("booking.redis.command-timeout-ms", "Infinity"),
                Arguments.of("booking.redis.command-timeout-ms", "99"),
                Arguments.of("booking.redis.command-timeout-ms", "30001"));
    }

    @Test
    void templateUsesStringBoundariesForJsonAsString() {
        RedissonClient client = mock(RedissonClient.class);
        runner.withPropertyValues("booking.redis.enabled=true", "booking.redis.host=redis.internal")
                .withBean(RedisClientFactory.class, () -> properties -> client)
                .run(context -> {
                    RedisTemplate<String, String> template = context.getBean(RedisTemplate.class);
                    assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
                    String json = "{\"type\":\"booking\",\"value\":1}";
                    @SuppressWarnings("unchecked")
                    org.springframework.data.redis.serializer.RedisSerializer<String> serializer =
                            (org.springframework.data.redis.serializer.RedisSerializer<String>) template.getValueSerializer();
                    byte[] encoded = serializer.serialize(json);
                    assertThat(serializer.deserialize(encoded)).isEqualTo(json);
                });
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
    void diagnosticsNeverContainPasswordOrCredentialUri(CapturedOutput output) {
        RedissonClient client = mock(RedissonClient.class);
        runner.withPropertyValues(
                        "booking.redis.enabled=true",
                        "booking.redis.host=redis.internal",
                        "booking.redis.password=super-secret")
                .withBean(RedisClientFactory.class, () -> properties -> client)
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("super-secret", "redis://:super-secret", "generated-secret");
    }

    @Test
    void redissonEndpointNeverCarriesPasswordOrBusinessKeys() {
        RedisProperties properties = new RedisProperties();
        properties.setEnabled(true);
        properties.setHost("redis.internal");
        properties.setPort(6380);
        properties.setPassword("secret-value");
        RedisFoundationConfiguration configuration = new RedisFoundationConfiguration();

        org.redisson.config.Config redissonConfig = configuration.createRedissonConfig(properties);
        String address = configuration.redissonAddress(properties);
        assertThat(address).isEqualTo("redis://redis.internal:6380");
        assertThat(address).doesNotContain("secret-value", "booking_slot", "cache");
        assertThat(redissonConfig.getLockWatchdogTimeout()).isEqualTo(30000L);
    }
}
