package com.marketingai.security;

import com.marketingai.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final AppProperties props;

    /** Fail at startup, not on the first login: HS256 needs ≥ 32 bytes (RFC 7518 §3.2). */
    @jakarta.annotation.PostConstruct
    void validateSecret() {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.startsWith("${") || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET is missing or shorter than 32 bytes — set the JWT_SECRET environment variable");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(
                props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generate(UUID userId, UUID tenantId, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getJwt().getExpiryMs()))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
