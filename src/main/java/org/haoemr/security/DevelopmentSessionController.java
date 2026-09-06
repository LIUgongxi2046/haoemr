package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import org.haoemr.contracts.SessionLoginRequestWire;
import org.haoemr.contracts.SessionLoginResponseWire;
import org.haoemr.contracts.SessionUserWire;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev-synthetic")
@RequestMapping("/api/v1/session")
final class DevelopmentSessionController {
    private final DevelopmentSessionService sessions;

    DevelopmentSessionController(DevelopmentSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/login")
    ResponseEntity<SessionLoginResponseWire> login(@RequestBody SessionLoginRequestWire request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.login(request));
    }

    @GetMapping("/current")
    ResponseEntity<SessionUserWire> current(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sessions.current(request.getHeader("Authorization")));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(request.getHeader("Authorization"));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

