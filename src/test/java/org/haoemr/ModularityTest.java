package org.haoemr;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

final class ModularityTest {

    @Test
    void applicationModulesRespectDeclaredBoundaries() {
        ApplicationModules.of(HaoEmrApplication.class).verify();
    }
}

