package com.ebithex.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines two HikariCP pools backed by the same PostgreSQL instance but
 * differentiated by {@code connectionInitSql}:
 *
 * <ul>
 *   <li>{@code prod}    — {@code SET search_path TO public}</li>
 *   <li>{@code sandbox} — {@code SET search_path TO sandbox, public}
 *       (public as fallback so shared tables are readable without schema prefix)</li>
 * </ul>
 *
 * The two pools are registered in {@link SchemaRoutingDataSource} which
 * selects the active pool per-request via {@code SandboxContextHolder}.
 *
 * Flyway uses the primary DataSource at startup; since no HTTP thread is active,
 * {@code SandboxContextHolder.isSandbox()} returns {@code false} and migrations
 * run against the {@code prod} pool (public schema).
 */
@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int prodMaxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:3}")
    private int prodMinIdle;

    @Value("${ebithex.datasource.sandbox.hikari.maximum-pool-size:5}")
    private int sandboxMaxPoolSize;

    @Value("${ebithex.datasource.sandbox.hikari.minimum-idle:1}")
    private int sandboxMinIdle;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariDataSource prod    = buildPool("HikariPool-prod",
                "SET search_path TO public",
                prodMaxPoolSize, prodMinIdle);
        HikariDataSource sandbox = buildPool("HikariPool-sandbox",
                "SET search_path TO sandbox, public",
                sandboxMaxPoolSize, sandboxMinIdle);

        Map<Object, Object> sources = new HashMap<>();
        sources.put("prod",    prod);
        sources.put("sandbox", sandbox);

        SchemaRoutingDataSource routing = new SchemaRoutingDataSource();
        routing.setTargetDataSources(sources);
        routing.setDefaultTargetDataSource(prod);
        routing.afterPropertiesSet();
        return routing;
    }

    private HikariDataSource buildPool(String poolName, String initSql,
                                        int maxSize, int minIdle) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setConnectionInitSql(initSql);
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(maxSize);
        cfg.setMinimumIdle(minIdle);
        return new HikariDataSource(cfg);
    }
}
