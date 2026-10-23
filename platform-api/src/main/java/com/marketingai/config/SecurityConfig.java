package com.marketingai.config;

import com.marketingai.security.JwtFilter;
import com.marketingai.service.TenantDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final AppProperties props;
    private final TenantDomainService domainService;   // roadmap Phase 10

    /** Roles allowed to mutate state (JwtFilter grants ROLE_<role>; VIEWER is read-only). */
    static final String[] WRITE_ROLES = {"ADMIN", "OWNER", "MARKETER", "EDITOR"};

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/events", "/webhooks/**", "/health", "/actuator/health", "/error", "/files/**", "/public/**").permitAll()
                // Audit §3.4: no role enforcement existed — a VIEWER could set Meta credentials or approve variants.
                // Tenant settings / credentials / agency accounts: ADMIN only. Any other write: not VIEWER.
                .requestMatchers(HttpMethod.PATCH,  "/tenants/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/tenants/**").hasRole("ADMIN")
                // S-11: ErasureController maps to /privacy/erasure (not /erasure/**) — fixed matcher
                .requestMatchers(HttpMethod.POST,   "/tenants/**", "/agency/**", "/privacy/erasure").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/tenants/**", "/agency/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,   "/**").hasAnyRole(WRITE_ROLES)
                .requestMatchers(HttpMethod.PUT,    "/**").hasAnyRole(WRITE_ROLES)
                .requestMatchers(HttpMethod.PATCH,  "/**").hasAnyRole(WRITE_ROLES)
                .requestMatchers(HttpMethod.DELETE, "/**").hasAnyRole(WRITE_ROLES)
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(401, "Unauthorized"))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Roadmap Phase 10: configured origins plus every VERIFIED tenant domain (landings on client domains post
     * events / forms to this API). Cached 60 s inside {@link TenantDomainCorsSource}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return new TenantDomainCorsSource(props.getCors().getAllowedOrigins(), domainService);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
