package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

final class SyntheticDiseaseCaseCatalogTest {

    @Test
    void placeholderProfilesReceiveStableNaturalChineseNamesBeforeDisplay() {
        var patientId = java.util.UUID.fromString("018f0000-0000-7000-8000-000000000001");
        assertThat(SyntheticDataImporter.realisticPatientName(patientId, "F"))
                .matches("\\p{IsHan}{3}")
                .isEqualTo(SyntheticDataImporter.realisticPatientName(patientId, "F"));
        assertThat(SyntheticDataImporter.normalizeSyntheticSex("1")).isEqualTo("M");
        assertThat(SyntheticDataImporter.normalizeSyntheticSex("2")).isEqualTo("F");
        assertThat(SyntheticDataImporter.normalizeSyntheticSex("U")).isEqualTo("U");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void givenFiftyDiseaseCatalog_whenParsed_thenDomainsAndClinicalDetailAreComplete() throws Exception {
        String json = Files.readString(Path.of("samples/data/synthetic-50-disease-cases-v1.json"));

        SyntheticDiseaseCaseCatalog catalog = SyntheticDiseaseCaseCatalog.parse(objectMapper, json);

        assertThat(catalog.cases()).hasSize(50);
        assertThat(catalog.cases()).extracting(SyntheticDiseaseCaseCatalog.DiseaseCase::diseaseCode)
                .doesNotHaveDuplicates();
        assertThat(catalog.cases()).extracting(SyntheticDiseaseCaseCatalog.DiseaseCase::patientName)
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("\\p{IsHan}{2,4}")
                        .doesNotContain("合成", "测试", "患者"));
        assertThat(catalog.cases()).filteredOn(item -> item.domain().equals("OUTPATIENT")).hasSize(20);
        assertThat(catalog.cases()).filteredOn(item -> item.domain().equals("EMERGENCY")).hasSize(15);
        assertThat(catalog.cases()).filteredOn(item -> item.domain().equals("INPATIENT")).hasSize(15);
        assertThat(catalog.cases()).allSatisfy(item -> {
            assertThat(item.presentIllness()).hasSizeGreaterThanOrEqualTo(20);
            assertThat(item.evidenceSummary()).hasSizeGreaterThanOrEqualTo(20);
            assertThat(item.treatmentPlan()).hasSizeGreaterThanOrEqualTo(20);
        });
    }

    @Test
    void givenNonSyntheticCatalog_whenParsed_thenImportIsBlocked() {
        assertThatIllegalArgumentException().isThrownBy(() -> SyntheticDiseaseCaseCatalog.parse(objectMapper, """
                {"dataset_version":"1","synthetic":false,"cases":[]}
                """));
    }
}
