package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Roadmap Phase 10: client-domain verification helpers and runner-key hashing. */
class DomainVerifyMathTest {

    @Test
    void normalize_strips_scheme_port_path_and_lowercases() {
        assertThat(DomainVerifyMath.normalizeHost("  HTTPS://Go.Client.com:443/l/x?y=1 ")).isEqualTo("go.client.com");
        assertThat(DomainVerifyMath.normalizeHost("go.client.com.")).isEqualTo("go.client.com");
        assertThat(DomainVerifyMath.normalizeHost("user@go.client.com")).isEqualTo("go.client.com");
    }

    @Test
    void normalize_rejects_localhost_ips_and_garbage() {
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("app.localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("10.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("[::1]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("nodots")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainVerifyMath.normalizeHost("bad_host.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void txt_record_name_and_matching() {
        assertThat(DomainVerifyMath.txtRecordName("go.client.com")).isEqualTo("_mai-verify.go.client.com");
        String token = "0123456789abcdef0123456789abcdef";
        assertThat(DomainVerifyMath.txtMatches(List.of("\"" + token + "\""), token)).isTrue();
        assertThat(DomainVerifyMath.txtMatches(List.of("v=spf1 -all", " " + token + " "), token)).isTrue();
        assertThat(DomainVerifyMath.txtMatches(List.of("other"), token)).isFalse();
        assertThat(DomainVerifyMath.txtMatches(List.of(), token)).isFalse();
        assertThat(DomainVerifyMath.txtMatches(null, token)).isFalse();
    }

    @Test
    void cname_matching_ignores_case_and_trailing_dot() {
        assertThat(DomainVerifyMath.cnameMatches(List.of("Landings.Example.com."), "landings.example.com")).isTrue();
        assertThat(DomainVerifyMath.cnameMatches(List.of("other.example.com."), "landings.example.com")).isFalse();
        assertThat(DomainVerifyMath.cnameMatches(List.of(), "landings.example.com")).isFalse();
        assertThat(DomainVerifyMath.cnameMatches(List.of("x."), "")).isFalse();
    }

    @Test
    void tokens_and_keys_have_the_documented_shape() {
        assertThat(DomainVerifyMath.newToken()).matches("^[0-9a-f]{32}$");
        assertThat(DomainVerifyMath.newToken()).isNotEqualTo(DomainVerifyMath.newToken());
        String key = DomainVerifyMath.newRunnerKey();
        assertThat(key).matches("^mrk_[0-9a-f]{40}$");
        // only the hash is stored; it is deterministic and 64 hex chars
        assertThat(DomainVerifyMath.sha256Hex(key)).matches("^[0-9a-f]{64}$").isEqualTo(DomainVerifyMath.sha256Hex(key));
        assertThat(DomainVerifyMath.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
