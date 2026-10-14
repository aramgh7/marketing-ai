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
}
