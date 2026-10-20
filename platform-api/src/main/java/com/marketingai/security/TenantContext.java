package com.marketingai.security;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Per-thread database scope, applied to every JDBC statement by {@link TenantScopedConnection}.
 *
 * <ul>
 *   <li><b>Tenant scope</b> ({@link #set}): {@code app.current_tenant} is set on the connection; RLS
 *       policies restrict every tenant table to that tenant. Set by {@code JwtFilter} per request.</li>
 *   <li><b>System scope</b> ({@link #runAsSystem}): {@code app.rls_bypass = 'on'}; policies let every
 *       row through. Used only by scheduled jobs (via {@code SchedulingConfig}) and the few bootstrap
 *       paths that run before a tenant is known (login, registration, refresh, public slug lookup,
 *       event ingestion). It is explicit and never the default for an HTTP request.</li>
 *   <li><b>No scope</b>: both GUCs cleared — every RLS table returns zero rows.</li>
 * </ul>
 *
 * Audit 3.1: the previous implementation issued {@code SET LOCAL} in {@code getConnection()}, which
 * PostgreSQL discards outside a transaction block, and the app connected as the superuser {@code mai},
 * so no policy was ever evaluated.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantId) {
        TENANT.set(tenantId);
    }

    public static String get() {
        return TENANT.get();
    }

    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    public static void clear() {
        TENANT.remove();
        SYSTEM.remove();
    }

    /** Run {@code task} with RLS bypass on this thread; previous scope is restored afterwards. */
    public static void runAsSystem(Runnable task) {
        callAsSystem(() -> { task.run(); return null; });
    }

    public static <T> T callAsSystem(Supplier<T> task) {
        String prevTenant = TENANT.get();
        Boolean prevSystem = SYSTEM.get();
        SYSTEM.set(Boolean.TRUE);
        try {
            return task.get();
        } finally {
            restore(prevTenant, prevSystem);
        }
    }

    /** Checked-exception variant for callers that throw. */
    public static <T> T callAsSystemChecked(Callable<T> task) throws Exception {
        String prevTenant = TENANT.get();
        Boolean prevSystem = SYSTEM.get();
        SYSTEM.set(Boolean.TRUE);
        try {
            return task.call();
        } finally {
            restore(prevTenant, prevSystem);
        }
    }

    /** Run {@code task} scoped to one tenant (no bypass); previous scope is restored afterwards. */
    public static void runAs(String tenantId, Runnable task) {
        String prevTenant = TENANT.get();
        Boolean prevSystem = SYSTEM.get();
        TENANT.set(tenantId);
        SYSTEM.remove();
        try {
            task.run();
        } finally {
            restore(prevTenant, prevSystem);
        }
    }

    private static void restore(String prevTenant, Boolean prevSystem) {
        if (prevTenant == null) TENANT.remove(); else TENANT.set(prevTenant);
        if (prevSystem == null) SYSTEM.remove(); else SYSTEM.set(prevSystem);
    }
}
