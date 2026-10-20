package com.marketingai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit 3.1: the tenant GUC must reach PostgreSQL for the statements that run, in autocommit mode
 * and inside transactions alike, and must never leak to the next borrower of a pooled connection.
 */
class TenantScopedConnectionTest {

    private static final String T1 = "11111111-1111-1111-1111-111111111111";

    private Connection raw;
    private PreparedStatement scopeStmt;
    private PreparedStatement queryStmt;
    private Connection scoped;

    @BeforeEach
    void setUp() throws SQLException {
        raw = mock(Connection.class);
        scopeStmt = mock(PreparedStatement.class);
        queryStmt = mock(PreparedStatement.class);
        when(raw.prepareStatement(TenantScopedConnection.APPLY_SQL)).thenReturn(scopeStmt);
        when(raw.prepareStatement("SELECT 1")).thenReturn(queryStmt);
        when(raw.isClosed()).thenReturn(false);
        scoped = TenantScopedConnection.wrap(raw);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void appliesTenantBeforeFirstStatement_andOnlyOnceWhileUnchanged() throws SQLException {
        TenantContext.set(T1);
        scoped.prepareStatement("SELECT 1");
        scoped.prepareStatement("SELECT 1");

        verify(raw, times(1)).prepareStatement(TenantScopedConnection.APPLY_SQL);
        verify(scopeStmt).setString(1, T1);
        verify(scopeStmt).setString(2, "off");
        InOrder order = inOrder(raw);
        order.verify(raw).prepareStatement(TenantScopedConnection.APPLY_SQL);
        order.verify(raw, times(2)).prepareStatement("SELECT 1");
    }

    @Test
    void reappliesWhenScopeChangesMidConnection() throws SQLException {
        TenantContext.set(T1);
        scoped.prepareStatement("SELECT 1");
        TenantContext.runAsSystem(() -> {
            try {
                scoped.prepareStatement("SELECT 1");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        ArgumentCaptor<String> bypass = ArgumentCaptor.forClass(String.class);
        verify(scopeStmt, times(2)).setString(eq(2), bypass.capture());
        assertThat(bypass.getAllValues()).containsExactly("off", "on");
    }

    @Test
    void noScope_appliesEmptyTenantSoRlsReturnsNothing() throws SQLException {
        scoped.prepareStatement("SELECT 1");
        verify(scopeStmt).setString(1, "");
        verify(scopeStmt).setString(2, "off");
    }

    @Test
    void closeResetsSessionForNextBorrower() throws SQLException {
        TenantContext.set(T1);
        scoped.prepareStatement("SELECT 1");
        scoped.close();

        // second APPLY_SQL call is the reset to ('', 'off')
        verify(raw, times(2)).prepareStatement(TenantScopedConnection.APPLY_SQL);
        ArgumentCaptor<String> tenants = ArgumentCaptor.forClass(String.class);
        verify(scopeStmt, times(2)).setString(eq(1), tenants.capture());
        assertThat(tenants.getAllValues()).containsExactly(T1, "");
        verify(raw).close();
    }

    @Test
    void closeWithoutStatementsDoesNotTouchTheSession() throws SQLException {
        scoped.close();
        verify(raw, never()).prepareStatement(anyString());
        verify(raw).close();
    }
}
