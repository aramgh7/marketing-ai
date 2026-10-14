package com.marketingai.security;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public class TenantAwareDataSource extends AbstractDataSource {

    private final DataSource delegate;

    public TenantAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = delegate.getConnection();
        applyTenant(conn);
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = delegate.getConnection(username, password);
        applyTenant(conn);
        return conn;
    }

    private void applyTenant(Connection conn) throws SQLException {
        String tenantId = TenantContext.get();
        String sql = (tenantId != null && isValidUuid(tenantId))
                ? "SET app.current_tenant = '" + tenantId + "'"
                : "RESET app.current_tenant";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
