package com.flightplan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("RedisConfig")
class RedisConfigTest {

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    @Test
    @DisplayName("RedisConnectionFactory bean is created successfully")
    void connectionFactoryBeanExists() {
        assertThat(redisConnectionFactory).isNotNull();
    }
}