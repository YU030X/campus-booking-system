package com.yu030x.booking.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

/**
 * Environment-backed, opt-in Redis settings shared by later lock and cache consumers.
 *
 * <p>Redis is not a correctness source. T07 must fail closed when this foundation is
 * unavailable, while T12 may use its MySQL/read-through fallback. JSON, when needed by
 * a consumer, is transported as an explicitly serialized String; this foundation never
 * enables Java native or polymorphic deserialization.</p>
 */
@ConfigurationProperties(prefix = "booking.redis")
@Validated
public class RedisProperties {
    private boolean enabled;
    private String host = "";

    @Min(value = 1, message = "booking.redis.port must be between 1 and 65535")
    @Max(value = 65535, message = "booking.redis.port must be between 1 and 65535")
    private int port = 6379;

    private String password = "";
    private int database;

    @Min(value = 100, message = "booking.redis.connect-timeout-ms must be between 100 and 10000")
    @Max(value = 10000, message = "booking.redis.connect-timeout-ms must be between 100 and 10000")
    private int connectTimeoutMs = 3000;

    @Min(value = 100, message = "booking.redis.command-timeout-ms must be between 100 and 30000")
    @Max(value = 30000, message = "booking.redis.command-timeout-ms must be between 100 and 30000")
    private int commandTimeoutMs = 5000;

    @PostConstruct
    void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(host)) {
            throw new IllegalStateException("booking.redis.host must be nonblank when booking.redis.enabled=true");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("booking.redis.port must be between 1 and 65535");
        }
        if (database != 0) {
            throw new IllegalStateException("booking.redis.database is fixed at 0");
        }
        if (connectTimeoutMs < 100 || connectTimeoutMs > 10000) {
            throw new IllegalStateException("booking.redis.connect-timeout-ms must be between 100 and 10000");
        }
        if (commandTimeoutMs < 100 || commandTimeoutMs > 30000) {
            throw new IllegalStateException("booking.redis.command-timeout-ms must be between 100 and 30000");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return StringUtils.hasText(password) ? password : null;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(int commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }
}
