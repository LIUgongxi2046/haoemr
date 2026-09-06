package org.haoemr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final long MAX_API_BODY_BYTES = 1_048_576;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        if (request.getRequestURI().startsWith("/api/") && request.getContentLengthLong() > MAX_API_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"REQUEST_BODY_TOO_LARGE\",\"category\":\"VALIDATION\",\"message\":\"Request body exceeds the clinical API limit\",\"trace_id\":\"rejected-before-processing\",\"retryable\":false,\"violations\":[]}}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
