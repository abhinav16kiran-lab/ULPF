package com.ulpf.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for the Spring Boot Actuator endpoints ({@code /actuator/**}), separate from the application chain in
 * {@link SpringSecurity}. {@code /actuator/health} is open. Every other actuator endpoint needs the monitor user
 * over HTTP Basic: {@code ulpf.monitor.username} (default {@code monitor}) and {@code ulpf.monitor.password}, from
 * {@code MONITOR_PASSWORD} or the secret file {@code /run/secrets/monitor_password}.
 *
 * <p>Fail closed: with no password, no monitor user exists and those endpoints are denied, so nothing is exposed by
 * accident. The monitor user lives in this chain's own authentication manager, not in a global bean, so it can never
 * sign in to the application, and a ULPF login token never opens the actuator ({@link JwtFilter} skips
 * {@code /actuator/**}). Metrics describe internals (queue depth, table names in counter tags), so they are not public.
 */
@Configuration
public class ActuatorSecurity {

    private static final Logger log = LoggerFactory.getLogger(ActuatorSecurity.class);

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http,
            @Value("${ulpf.monitor.username:monitor}") String username,
            @Value("${ulpf.monitor.password:}") String password,
            BCryptPasswordEncoder encoder) throws Exception {
        http.securityMatcher("/actuator/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (password == null || password.isBlank()) {
            log.warn("No monitor password set (MONITOR_PASSWORD or /run/secrets/monitor_password): "
                    + "actuator endpoints other than /actuator/health are denied");
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().denyAll());
            return http.build();
        }

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("MONITOR").build()));
        provider.setPasswordEncoder(encoder);
        http.authenticationManager(new ProviderManager(provider))
            .httpBasic(basic -> { })
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().hasRole("MONITOR"));
        return http.build();
    }
}
