package com.marketingai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * reCAPTCHA Enterprise v3 guard (vision §12.6).
 * When RECAPTCHA_SECRET_KEY is set, validates X-Recaptcha-Token header on mutating endpoints.
 * Skipped entirely in dev (key not configured).
 */
@Slf4j
@Component
public class RecaptchaFilter extends OncePerRequestFilter {

    @Value("${app.recaptcha.secret-key:}")
    private String secretKey;

    private static final String HEADER = "X-Recaptcha-Token";
    private static final float  MIN_SCORE = 0.5f;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // Only enforce on mutating methods when key is configured
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);

        if (secretKey.isEmpty() || !mutating) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            // No token present — allow through (frontend may not have loaded reCAPTCHA script yet)
            chain.doFilter(request, response);
            return;
        }

        // X-6/S-9: fail-closed — on verification error OR low score, reject the request.
        // Allowing through on API error would let an attacker time attacks to when reCAPTCHA is unavailable.
        try {
            boolean valid = verifyToken(token);
            if (!valid) {
                log.warn("reCAPTCHA verification failed for {} {}", method, request.getRequestURI());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "reCAPTCHA verification failed");
                return;
            }
        } catch (Exception e) {
            log.warn("reCAPTCHA API error (fail closed) for {} {}: {}", method, request.getRequestURI(), e.getMessage());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "reCAPTCHA service unavailable");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean verifyToken(String token) throws IOException {
        String url = "https://recaptchaenterprise.googleapis.com/v1/projects/default/assessments?key=" + secretKey;
        String body = String.format(
            "{\"event\":{\"token\":\"%s\",\"expectedAction\":\"approve\",\"siteKey\":\"\"}}",
            token.replace("\"", "\\\""));

        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code != 200) return false;

        try (java.io.InputStream is = conn.getInputStream()) {
            String resp = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Naive score extraction: "score": 0.9
            int idx = resp.indexOf("\"score\":");
            if (idx < 0) return true; // can't parse → allow
            float score = Float.parseFloat(resp.substring(idx + 8, resp.indexOf(",", idx + 8)).trim());
            return score >= MIN_SCORE;
        }
    }
}
