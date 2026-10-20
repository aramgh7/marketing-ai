package com.marketingai.security;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the Hikari pool so every handed-out connection applies the thread's {@link TenantContext}
 * scope to the PostgreSQL session before each statement (see {@link TenantScopedConnection}).
 *
 * Audit 3.1: the previous version ran {@code SET LOCAL} inside {@code getConnection()}. HikariCP hands
 * out connections in autocommit mode and Spring begins the transaction afterwards, so PostgreSQL
 * discarded the setting ("SET LOCAL can only be used in transaction blocks") and
 * {@code app.current_tenant} was never set for any query.
 */
public class TenantAwareDataSource extends AbstractDataSource {

    private final DataSource delegate;

    public TenantAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return TenantScopedConnection.wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return TenantScopedConnection.wrap(delegate.getConnection(username, password));
    }
}
