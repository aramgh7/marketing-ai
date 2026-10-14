package com.marketingai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    private String tenantName;

    @NotBlank
    @Size(min = 3, max = 50, message = "Slug must be 3-50 characters")
    private String tenantSlug;

    @Email
    @NotBlank
    private String email;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
