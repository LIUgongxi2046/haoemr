package org.haoemr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class SystemReadinessTest {

    @Test
    void givenADevelopmentBuild_whenReadinessIsCreated_thenItNamesTheClinicalCore() {
        var readiness = SystemReadiness.ready("0.1.0-SNAPSHOT");

        assertThat(readiness.product()).isEqualTo("haoemr");
        assertThat(readiness.component()).isEqualTo("clinical-core");
        assertThat(readiness.status()).isEqualTo("READY");
    }
}

