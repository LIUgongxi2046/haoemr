package org.haoemr.security;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

final class SecurityHeadersFilterTest {
    private final SecurityHeadersFilter filter=new SecurityHeadersFilter(request -> new ClinicalIdentity(UUID.randomUUID(),UUID.randomUUID(),List.of(UUID.randomUUID())));
    @Test void onlyTheExactAuthenticatedPrecheckCanReceiveLargeJson() throws Exception {
        byte[] payload=new byte[10*1024*1024+1024];
        var request=new MockHttpServletRequest("POST","/api/v1/admin/configuration-packs/prechecks");request.setContent(payload);
        var response=new MockHttpServletResponse();var invoked=new AtomicBoolean();
        filter.doFilter(request,response,(req,res)->{assertThat(req.getInputStream().readAllBytes()).hasSize(payload.length);invoked.set(true);});
        assertThat(invoked).isTrue();assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        for(String path:List.of("/api/v1/documents","/api/v1/admin/configuration-packs/operations","/api/v1/admin/configuration-packs/prechecks/extra")) {
            var ordinary=new MockHttpServletRequest("POST",path);ordinary.setContent(payload);var rejected=new MockHttpServletResponse();
            filter.doFilter(ordinary,rejected,(req,res)->{throw new AssertionError("must not reach controller");});assertThat(rejected.getStatus()).isEqualTo(413);
        }
    }
    @Test void unauthenticatedPrecheckNeverReadsItsBody() throws Exception {
        var guarded=new SecurityHeadersFilter(request->{throw new ClinicalAccessDeniedException("AUTHENTICATION_REQUIRED","denied");});
        var request=new MockHttpServletRequest("POST","/api/v1/admin/configuration-packs/prechecks") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("unauthenticated body must not be read"); }
        };
        var response=new MockHttpServletResponse();guarded.doFilter(request,response,(req,res)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(401);
    }
    @Test void chunkedOrMisdeclaredPrecheckCannotBypassActualByteLimit() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/v1/admin/configuration-packs/prechecks") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[6*10*1024*1024+65_537]);var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("over-limit stream must be rejected");});
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
