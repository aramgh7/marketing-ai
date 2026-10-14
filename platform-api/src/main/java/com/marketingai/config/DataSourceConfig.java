package com.marketingai.config;

import com.marketingai.security.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource hikari = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new TenantAwareDataSource(hikari);
    }
}
