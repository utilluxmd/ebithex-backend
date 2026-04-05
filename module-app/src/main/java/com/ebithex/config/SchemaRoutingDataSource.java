package com.ebithex.config;

import com.ebithex.shared.sandbox.SandboxContextHolder;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each JDBC connection to the {@code prod} or {@code sandbox} pool
 * based on the current request's {@link SandboxContextHolder} flag.
 *
 * Pool keys: {@code "prod"} → {@code SET search_path TO public}
 *            {@code "sandbox"} → {@code SET search_path TO sandbox, public}
 */
public class SchemaRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return SandboxContextHolder.isSandbox() ? "sandbox" : "prod";
    }
}
