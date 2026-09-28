package org.haoemr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final long MAX_API_BODY_BYTES = 1_048_576;
    // JSON-encoding the 10 MB manifest string can expand a character to six bytes (\u0009 etc.).
    private static final int MAX_PACK_ENVELOPE_BYTES = 6 * 10 * 1024 * 1024 + 65_536;
    private final ClinicalIdentityProvider identities;

    SecurityHeadersFilter(ClinicalIdentityProvider identities) { this.identities = identities; }

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
        boolean packPrecheck = "POST".equals(request.getMethod()) && "/api/v1/admin/configuration-packs/prechecks".equals(request.getRequestURI());
        if (packPrecheck) {
            try { identities.current(request); }
            catch (ClinicalAccessDeniedException denied) {
                response.setStatus("AUTHENTICATION_REQUIRED".equals(denied.code()) ? 401 : 403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Clinical authentication required\"}}");
                return;
            }
        }
        long limit = packPrecheck ? MAX_PACK_ENVELOPE_BYTES : MAX_API_BODY_BYTES;
        if (request.getRequestURI().startsWith("/api/") && request.getContentLengthLong() > limit) {
            rejectOversized(response); return;
        }
        if (packPrecheck) {
            // Bound actual bytes too: chunked requests cannot bypass the envelope limit.
            byte[] bytes = request.getInputStream().readNBytes(MAX_PACK_ENVELOPE_BYTES + 1);
            if (bytes.length > MAX_PACK_ENVELOPE_BYTES) { rejectOversized(response); return; }
            var buffer = new ByteArrayInputStream(bytes);
            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        @Override public int read() { return buffer.read(); }
                        @Override public int read(byte[] target, int offset, int length) { return buffer.read(target, offset, length); }
                        @Override public boolean isFinished() { return buffer.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON endpoint"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
            };
            filterChain.doFilter(wrapped, response); return;
        }
        filterChain.doFilter(request, response);
    }
    private static void rejectOversized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"REQUEST_BODY_TOO_LARGE\",\"category\":\"VALIDATION\",\"message\":\"Request body exceeds the endpoint limit\",\"trace_id\":\"rejected-before-processing\",\"retryable\":false,\"violations\":[]}}");
    }
}
