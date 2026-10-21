package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.entity.RunnerKey;
import com.marketingai.entity.TenantDomain;
import com.marketingai.repository.RunnerKeyRepository;
import com.marketingai.repository.TenantDomainRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap Phase 10: client domains for landings.
 *
 *  CNAME        — client points host → app.landing.cname-target; our Next.js edge serves the landing on the
 *                 client's domain (Meta sees only the client's domain; a block never spreads across clients).
 *  SELF_HOSTED  — the client runs the landing runner on their own server; it talks to us with a runner key
 *                 (/public/decide, /public/l/{slug}) — DNS TXT verification only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TenantDomainService {

    public static final Set<String> KINDS = Set.of("LANDING", "COLLECT");
    public static final Set<String> HOSTINGS = Set.of("CNAME", "SELF_HOSTED");

    private final TenantDomainRepository domainRepo;
    private final RunnerKeyRepository keyRepo;
    private final DnsLookup dns;
    private final AppProperties props;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return domainRepo.findAllByTenantIdOrderByCreatedAtAsc(currentTenant()).stream().map(this::toMap).toList();
    }

    public Map<String, Object> create(String host, String kind, String hosting, String defaultSlug) {
        String h;
        try {
            h = DomainVerifyMath.normalizeHost(host);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String k = kind == null || kind.isBlank() ? "LANDING" : kind.toUpperCase();
        String hs = hosting == null || hosting.isBlank() ? "CNAME" : hosting.toUpperCase();
        if (!KINDS.contains(k)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be LANDING or COLLECT");
        if (!HOSTINGS.contains(hs)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hosting must be CNAME or SELF_HOSTED");
        boolean taken = TenantContext.callAsSystem(() -> domainRepo.findByHost(h).isPresent());
        if (taken) throw new ResponseStatusException(HttpStatus.CONFLICT, "Host already registered: " + h);

        TenantDomain d = new TenantDomain();
        d.setTenantId(currentTenant());
        d.setHost(h);
        d.setKind(k);
        d.setHosting(hs);
        d.setDefaultSlug(defaultSlug == null || defaultSlug.isBlank() ? null : defaultSlug.trim());
        d.setVerificationToken(DomainVerifyMath.newToken());
        d.setStatus("PENDING");
        return toMap(domainRepo.save(d));
    }

    public Map<String, Object> verify(UUID id) {
        TenantDomain d = owned(id);
        d.setLastCheckAt(Instant.now());
        String txtName = DomainVerifyMath.txtRecordName(d.getHost());
        List<String> txt = dns.txt(txtName);
        String error = null;
        if (!DomainVerifyMath.txtMatches(txt, d.getVerificationToken())) {
            error = "TXT record " + txtName + " does not contain the verification token"
                    + (txt.isEmpty() ? " (no TXT record found)" : "");
        } else if ("CNAME".equals(d.getHosting())) {
            List<String> cn = dns.cname(d.getHost());
            String target = props.getLanding().getCnameTarget();
            if (!DomainVerifyMath.cnameMatches(cn, target)) {
                error = "CNAME for " + d.getHost() + " must point to " + target
                        + (cn.isEmpty() ? " (no CNAME record found)" : " (found: " + String.join(", ", cn) + ")");
            }
        }
        if (error == null) {
            d.setStatus("VERIFIED");
            d.setVerifiedAt(Instant.now());
            d.setLastError(null);
            log.info("Domain {} verified for tenant {}", d.getHost(), d.getTenantId());
        } else {
            d.setStatus("FAILED");
            d.setLastError(error);
        }
        return toMap(domainRepo.save(d));
    }

    public void delete(UUID id) {
        domainRepo.delete(owned(id));
    }

    public Map<String, Object> disable(UUID id) {
        TenantDomain d = owned(id);
        d.setStatus("DISABLED");
        return toMap(domainRepo.save(d));
    }

    /** Returns the plaintext key once; only its SHA-256 is stored. */
    public Map<String, Object> issueRunnerKey(UUID domainId, String label) {
        TenantDomain d = owned(domainId);
        String key = DomainVerifyMath.newRunnerKey();
        RunnerKey rk = new RunnerKey();
        rk.setTenantId(d.getTenantId());
        rk.setDomainId(d.getId());
        rk.setKeyHash(DomainVerifyMath.sha256Hex(key));
        rk.setLabel(label == null || label.isBlank() ? d.getHost() : label.trim());
        rk = keyRepo.save(rk);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("label", rk.getLabel());
        out.put("created_at", rk.getCreatedAt());
        return out;
    }

    // ─── public (unauthenticated) lookups — system scope ─────────────────────

    /** VERIFIED domain for a request host, if any. */
    @Transactional(readOnly = true)
    public Optional<TenantDomain> resolvePublic(String host) {
        if (host == null || host.isBlank()) return Optional.empty();
        String h;
        try {
            h = DomainVerifyMath.normalizeHost(host);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return TenantContext.callAsSystem(() -> domainRepo.findByHost(h).filter(d -> "VERIFIED".equals(d.getStatus())));
    }

    /** Tenant that owns a runner key; updates last_seen_at best-effort. */
    public Optional<UUID> tenantForRunnerKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String hash = DomainVerifyMath.sha256Hex(key.trim());
        return TenantContext.callAsSystem(() -> {
            Optional<RunnerKey> rk = keyRepo.findByKeyHashAndRevokedAtIsNull(hash);
            rk.ifPresent(k -> {
                try {
                    k.setLastSeenAt(Instant.now());
                    keyRepo.save(k);
                } catch (Exception e) {
                    log.debug("runner key last_seen update skipped: {}", e.getMessage());
                }
            });
            return rk.map(RunnerKey::getTenantId);
        });
    }

    /** Hosts of all VERIFIED domains (for CORS). */
    @Transactional(readOnly = true)
    public List<String> verifiedHosts() {
        return TenantContext.callAsSystem(() -> domainRepo.findAllByStatus("VERIFIED").stream().map(TenantDomain::getHost).toList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    public Map<String, Object> toMap(TenantDomain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("host", d.getHost());
        m.put("kind", d.getKind());
        m.put("hosting", d.getHosting());
        m.put("default_slug", d.getDefaultSlug());
        m.put("status", d.getStatus());
        m.put("verified_at", d.getVerifiedAt());
        m.put("last_check_at", d.getLastCheckAt());
        m.put("last_error", d.getLastError());
        m.put("cname_target", props.getLanding().getCnameTarget());
        m.put("txt_name", DomainVerifyMath.txtRecordName(d.getHost()));
        m.put("txt_value", d.getVerificationToken());
        m.put("created_at", d.getCreatedAt());
        return m;
    }

    private TenantDomain owned(UUID id) {
        return domainRepo.findByIdAndTenantId(id, currentTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found"));
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
