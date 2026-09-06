package org.haoemr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
final class SystemReadinessController {

    private final String version;

    SystemReadinessController(@Value("${haoemr.version:development}") String version) {
        this.version = version;
    }

    @GetMapping("/readiness")
    SystemReadiness readiness() {
        return SystemReadiness.ready(version);
    }
}

