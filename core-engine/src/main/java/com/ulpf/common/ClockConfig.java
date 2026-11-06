package com.ulpf.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the application {@link Clock} (UTC). Services that deal with expiry (admin links) take the clock
 * as a dependency, so tests can move time forward instead of waiting.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
