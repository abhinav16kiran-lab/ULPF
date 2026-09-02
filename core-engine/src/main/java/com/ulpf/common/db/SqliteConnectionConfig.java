package com.ulpf.common.db;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite primary connection pool and JdbcTemplate configuration.
 */
@Configuration
public class SqliteConnectionConfig {

    @Value("${spring.datasource.url:jdbc:sqlite:./data/control-plane.db?foreign_keys=on}")
    private String url;

    @Value("${spring.datasource.driver-class-name:org.sqlite.JDBC}")
    private String driverClassName;

    @Bean
    @Primary
    public DataSource sqliteDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource sqliteDataSource) {
        return new JdbcTemplate(sqliteDataSource);
    }
}
