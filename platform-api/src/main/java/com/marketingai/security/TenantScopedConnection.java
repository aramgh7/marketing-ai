package com.marketingai.security;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC {@link Connection} proxy that applies the current {@link TenantContext} scope to the PostgreSQL
 * session <em>before every statement</em>, re-issuing {@code set_config} only when the scope changed.
 *
 * Session-level ({@code is_local = false}) so the setting holds in autocommit mode (plain JdbcTemplate
 * calls) and inside transactions alike, and is correct even when Hibernate acquired the connection at
 * transaction begin — before the service body set the scope. Because every statement re-checks the
 * thread scope, a pooled connection can never carry a stale tenant into the next borrower.
 */
public final class TenantScopedConnection implements InvocationHandler {

    static final String APPLY_SQL =
            "SELECT set_config('app.current_tenant', ?, false), set_config('app.rls_bypass', ?, false)";

    private static final Set<String> STATEMENT_FACTORIES =
            Set.of("createStatement", "prepareStatement", "prepareCall", "nativeSQL");

    private final Connection delegate;
    private String appliedTenant = null;   // null = never applied on this checkout
    private String appliedBypass = null;

    private TenantScopedConnection(Connection delegate) {
        this.delegate = delegate;
    }

    public static Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantScopedConnection(raw));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (STATEMENT_FACTORIES.contains(name)) {
            ensureScope();
        } else if ("close".equals(name)) {
            // Leave the pooled session clean for the next borrower.
            resetScopeQuietly();
        } else if ("unwrap".equals(name) && args != null && args.length == 1
                && ((Class<?>) args[0]).isInstance(delegate)) {
            return delegate;
        } else if ("isWrapperFor".equals(name) && args != null && args.length == 1
                && ((Class<?>) args[0]).isInstance(delegate)) {
            return true;
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Current desired scope as (tenant, bypass) strings; package-private for tests. */
    static String[] desiredScope() {
        String tenant = TenantContext.get();
        String tenantValue = (tenant != null && isValidUuid(tenant)) ? tenant : "";
        String bypass = TenantContext.isSystem() ? "on" : "off";
        return new String[]{tenantValue, bypass};
    }

    private void ensureScope() throws SQLException {
        String[] want = desiredScope();
        if (Objects.equals(want[0], appliedTenant) && Objects.equals(want[1], appliedBypass)) {
            return;
        }
        try (PreparedStatement ps = delegate.prepareStatement(APPLY_SQL)) {
            ps.setString(1, want[0]);
            ps.setString(2, want[1]);
            ps.execute();
        }
        appliedTenant = want[0];
        appliedBypass = want[1];
    }

    private void resetScopeQuietly() {
        boolean clean = "".equals(appliedTenant) && "off".equals(appliedBypass);
        if (appliedTenant == null || clean) {
            return;
        }
        try {
            if (!delegate.isClosed()) {
                try (PreparedStatement ps = delegate.prepareStatement(APPLY_SQL)) {
                    ps.setString(1, "");
                    ps.setString(2, "off");
                    ps.execute();
                }
            }
        } catch (SQLException ignored) {
            // Connection is being returned/evicted; the next borrower re-applies its own scope anyway.
        }
        appliedTenant = "";
        appliedBypass = "off";
    }

    private static boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
