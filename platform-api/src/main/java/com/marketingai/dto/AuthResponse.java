package com.marketingai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private UUID userId;
    private UUID tenantId;
    private String role;
    private String email;
    /** Opaque rotation token for POST /auth/refresh (audit 7.3: it was never issued at login). */
    private String refreshToken;

    public AuthResponse(String token, UUID userId, UUID tenantId, String role, String email) {
        this(token, userId, tenantId, role, email, null);
    }
}
