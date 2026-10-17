package com.marketingai.service;

import com.marketingai.dto.AuthResponse;
import com.marketingai.dto.LoginRequest;
import com.marketingai.dto.RegisterRequest;
import com.marketingai.entity.Tenant;
import com.marketingai.entity.User;
import com.marketingai.repository.TenantRepository;
import com.marketingai.repository.UserRepository;
import com.marketingai.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbc;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long REFRESH_EXPIRY_DAYS = 30L;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (tenantRepo.existsBySlug(req.getTenantSlug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant slug already taken");
        }
        if (userRepo.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        Tenant tenant = new Tenant();
        tenant.setName(req.getTenantName());
        tenant.setSlug(req.getTenantSlug());
        tenant = tenantRepo.save(tenant);

        User user = new User();
        user.setTenant(tenant);
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setRole("ADMIN");
        user = userRepo.save(user);

        String token = jwtUtil.generate(user.getId(), tenant.getId(), user.getRole());
        return new AuthResponse(token, user.getId(), tenant.getId(), user.getRole(), user.getEmail());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepo.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is not active");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String token = jwtUtil.generate(user.getId(), user.getTenant().getId(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getTenant().getId(), user.getRole(), user.getEmail());
    }

    // ─── Refresh Token ────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refresh(String rawToken) {
        String hash = sha256Hex(rawToken);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT rt.id, rt.user_id, rt.tenant_id
                FROM refresh_tokens rt
                WHERE rt.token_hash = ? AND NOT rt.revoked AND rt.expires_at > NOW()
                """, hash);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        UUID tokenId = UUID.fromString(rows.get(0).get("id").toString());
        UUID userId  = UUID.fromString(rows.get(0).get("user_id").toString());
        UUID tenantId = UUID.fromString(rows.get(0).get("tenant_id").toString());

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // Revoke old token (rotation)
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE id = ?", tokenId);

        // Issue new access + refresh tokens
        String newAccess  = jwtUtil.generate(userId, tenantId, user.getRole());
        String newRefresh = issueRefreshToken(userId, tenantId);

        AuthResponse resp = new AuthResponse(newAccess, userId, tenantId, user.getRole(), user.getEmail());
        // Attach refresh token in the access token field — callers should use the separate refresh field
        // We add a custom field by extending AuthResponse or returning a wrapper.
        // For now, embed refresh token in a response wrapper via an extended class.
        return new AuthResponseWithRefresh(newAccess, userId, tenantId, user.getRole(), user.getEmail(), newRefresh);
    }

    @Transactional
    public void logout(String rawToken) {
        String hash = sha256Hex(rawToken);
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE token_hash = ?", hash);
    }

    /** Issues a new refresh token, persists its hash, returns the raw token. */
    public String issueRefreshToken(UUID userId, UUID tenantId) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String raw  = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(raw);

        jdbc.update("""
                INSERT INTO refresh_tokens (id, tenant_id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, NOW() + INTERVAL '30 days')
                """, UUID.randomUUID(), tenantId, userId, hash);

        return raw;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // ─── Inner DTO with refresh token ────────────────────────────────────────

    public static class AuthResponseWithRefresh extends AuthResponse {
        private final String refreshToken;
        public AuthResponseWithRefresh(String token, UUID userId, UUID tenantId,
                                        String role, String email, String refreshToken) {
            super(token, userId, tenantId, role, email);
            this.refreshToken = refreshToken;
        }
        public String getRefreshToken() { return refreshToken; }
    }
}
