package com.yu030x.booking.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationStaticTest {
    @Test
    void usesEnvironmentOnlyDatabaseAndDisabledDocs() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("url: ${DB_URL}", "username: ${DB_USERNAME}", "password: ${DB_PASSWORD}")
                .contains("characterEncoding: UTF-8")
                .contains("enabled: ${SPRINGDOC_ENABLED:false}")
                .contains("org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
                .contains("org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration")
                .contains("org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
                .doesNotContain("jdbc:h2", "password: root");
    }
}
