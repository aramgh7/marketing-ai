package com.marketingai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void defaultScopeIsEmpty() {
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantScopedConnection.desiredScope()).containsExactly("", "off");
    }

    @Test
    void tenantScopeIsAppliedOnlyForValidUuid() {
        TenantContext.set("11111111-1111-1111-1111-111111111111");
        assertThat(TenantScopedConnection.desiredScope())
                .containsExactly("11111111-1111-1111-1111-111111111111", "off");

        TenantContext.set("not-a-uuid'; DROP TABLE tenants; --");
        assertThat(TenantScopedConnection.desiredScope()).containsExactly("", "off");
    }

    @Test
    void systemScopeIsExplicitAndRestoresPreviousScope() {
        TenantContext.set("22222222-2222-2222-2222-222222222222");
        String inside = TenantContext.callAsSystem(() -> {
            assertThat(TenantContext.isSystem()).isTrue();
            return TenantScopedConnection.desiredScope()[1];
        });
        assertThat(inside).isEqualTo("on");
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.get()).isEqualTo("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void systemScopeIsRestoredAfterException() {
        try {
            TenantContext.runAsSystem(() -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // fine
        }
        assertThat(TenantContext.isSystem()).isFalse();
    }

    @Test
    void runAsDropsSystemScopeForTheTask() {
        TenantContext.runAsSystem(() ->
                TenantContext.runAs("33333333-3333-3333-3333-333333333333", () -> {
                    assertThat(TenantContext.isSystem()).isFalse();
                    assertThat(TenantContext.get()).isEqualTo("33333333-3333-3333-3333-333333333333");
                }));
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.get()).isNull();
    }
}
