package com.marketingai.service;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap 9-1: when {@code ENCRYPTION_KEY} is configured, legacy plaintext token rows are sealed once at
 * startup (system scope — the rows belong to every tenant). Without the key nothing runs.
 * Idempotent: only rows whose bytes do not start with {@code enc1:} are touched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenReencryptRunner implements ApplicationRunner {

    /** table, pk column, token column */
    static final String[][] TARGETS = {
            {"tenants",         "id", "meta_access_token_enc"},
            {"meta_pixels",     "id", "capi_access_token_enc"},
            {"ghl_subaccounts", "id", "api_key_enc"},
            {"channel_accounts","id", "access_token_enc"},
    };

    private final JdbcTemplate jdbc;
    private final TokenVault vault;

    @Override
    public void run(ApplicationArguments args) {
        if (!vault.isActive()) return;
        TenantContext.runAsSystem(() -> {
            int total = 0;
            for (String[] t : TARGETS) {
                try {
                    total += reseal(t[0], t[1], t[2]);
                } catch (Exception e) {
                    log.warn("TokenReencryptRunner: {}.{} skipped: {}", t[0], t[2], e.getMessage());
                }
            }
            if (total > 0) log.info("TokenReencryptRunner: sealed {} legacy token rows", total);
        });
    }

    int reseal(String table, String pk, String col) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + pk + " AS id, " + col + " AS tok FROM " + table +
                " WHERE " + col + " IS NOT NULL AND length(" + col + ") > 0" +
                " AND substring(" + col + " from 1 for 5) <> '\\x656e63313a'::bytea");
        int n = 0;
        for (Map<String, Object> row : rows) {
            byte[] raw = (byte[]) row.get("tok");
            if (raw == null || TokenVault.isSealed(raw)) continue;
            byte[] sealed = vault.seal(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("UPDATE " + table + " SET " + col + " = ? WHERE " + pk + " = ?", sealed, (UUID) row.get("id"));
            n++;
        }
        return n;
    }
}
