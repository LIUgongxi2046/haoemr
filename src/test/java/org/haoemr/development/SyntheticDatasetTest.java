package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

final class SyntheticDatasetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void givenAnExplicitSyntheticDataset_whenParsed_thenCasesAreAvailable() throws Exception {
        var dataset = SyntheticDataset.parse(objectMapper, """
                {"dataset_version":"1","synthetic":true,"cases":[{"case_id":"syn-1"}]}
                """);

        assertThat(dataset.synthetic()).isTrue();
        assertThat(dataset.cases()).hasSize(1);
    }

    @Test
    void givenDataWithoutTheSyntheticFlag_whenParsed_thenImportIsBlocked() {
        assertThatIllegalArgumentException().isThrownBy(() -> SyntheticDataset.parse(objectMapper, """
                {"dataset_version":"1","synthetic":false,"cases":[]}
                """));
    }
}
