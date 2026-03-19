package com.flightplan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mock")
@DisplayName("RedisConfig")
class RedisConfigTest {

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    @Test
    @DisplayName("RedisConnectionFactory bean is created successfully without password")
    void connectionFactoryBeanExists() {
        assertThat(redisConnectionFactory).isNotNull();
    }

    @Test
    @DisplayName("RedisConnectionFactory bean is created successfully with password")
    void connectionFactoryBeanExistsWithPassword() {
        // Directly instantiate to exercise the password branch
        RedisConfig config = new RedisConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "redisHost", "localhost");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "redisPort", 6379);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "redisPassword", "test-secret");

        RedisConnectionFactory factory = config.redisConnectionFactory();

        assertThat(factory).isNotNull();
    }
}