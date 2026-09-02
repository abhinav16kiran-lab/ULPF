package com.ulpf.common.db;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ClickHouse secondary connection pool and dedicated clickhouseJdbcTemplate configuration.
 */
@Configuration
public class ClickHouseConnectionConfig {

    @Value("${clickhouse.datasource.url:jdbc:clickhouse://localhost:8123/ulpf_raw}")
    private String url;

    @Value("${clickhouse.datasource.driver-class-name:com.clickhouse.jdbc.ClickHouseDriver}")
    private String driverClassName;

    @Value("${clickhouse.datasource.username:default}")
    private String username;

    @Value("${clickhouse.datasource.password:}")
    private String password;

    @Bean
    @Qualifier("clickhouseDataSource")
    public DataSource clickhouseDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .driverClassName(driverClassName)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    @Qualifier("clickhouseJdbcTemplate")
    public JdbcTemplate clickhouseJdbcTemplate(@Qualifier("clickhouseDataSource") DataSource clickhouseDataSource) {
        return new JdbcTemplate(clickhouseDataSource);
    }
}
