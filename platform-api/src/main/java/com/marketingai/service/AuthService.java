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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

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
}
