package com.marketingai.config;

import com.marketingai.security.JwtFilter;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final AppProperties props;

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
                .requestMatchers(HttpMethod.POST,   "/tenants/**", "/agency/**", "/erasure/**").hasRole("ADMIN")
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

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(props.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
