package org.haoemr.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityRuleDeactivateRequestWire;
import org.haoemr.contracts.DataQualityRuleRegisterRequestWire;
import org.haoemr.contracts.DataQualityRuleWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class DataQualityRuleApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DataQualityRuleService rules;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private DataQualityRuleWire register(String ruleCode, String dimension, double threshold, String severity) {
        return rules.register(identity(), "dqr-" + UUID.randomUUID(),
                new DataQualityRuleRegisterRequestWire(organization, facility, ruleCode,
                        "规则-" + ruleCode,
                        DataQualityRuleRegisterRequestWire.DimensionValue.valueOf(dimension),
                        "clinical_document", threshold,
                        DataQualityRuleRegisterRequestWire.SeverityValue.valueOf(severity)));
    }

    @Test
    void givenRule_whenRegisteringAndListing_thenActiveRuleRecorded() {
        String ruleCode = "DQ-" + UUID.randomUUID().toString().substring(0, 8);
        DataQualityRuleWire registered = register(ruleCode, "COMPLETENESS", 0.95, "WARNING");
        assertThat(registered.status()).isEqualTo(DataQualityRuleWire.StatusValue.ACTIVE);
        assertThat(registered.dimension()).isEqualTo(DataQualityRuleWire.DimensionValue.COMPLETENESS);
        assertThat(registered.threshold()).isEqualTo(0.95);

        List<DataQualityRuleWire> listed = rules.listRules(identity(), "COMPLETENESS");
        assertThat(listed).extracting(DataQualityRuleWire::dataQualityRuleId).contains(registered.dataQualityRuleId());
    }

    @Test
    void givenActiveRule_whenDeactivating_thenInactive() {
        String ruleCode = "DQ-" + UUID.randomUUID().toString().substring(0, 8);
        DataQualityRuleWire registered = register(ruleCode, "UNIQUENESS", 1.0, "BLOCKING");
        DataQualityRuleWire deactivated = rules.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.dataQualityRuleId(), new DataQualityRuleDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(DataQualityRuleWire.StatusValue.INACTIVE);
    }

    @Test
    void givenOutOfRangeThreshold_whenRegistering_thenRejected() {
        assertThatThrownBy(() -> register("DQ-" + UUID.randomUUID().toString().substring(0, 8), "VALIDITY", 1.5, "INFO"))
                .isInstanceOf(DataQualityRuleException.class)
                .satisfies(e -> assertThat(((DataQualityRuleException) e).code())
                        .isEqualTo("DATA_QUALITY_RULE_REQUEST_INVALID"));
    }

    @Test
    void givenRuleIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String ruleCode = "DQ-" + UUID.randomUUID().toString().substring(0, 8);
        DataQualityRuleWire registered = register(ruleCode, "TIMELINESS", 0.9, "WARNING");
        assertThatThrownBy(() -> jdbc.sql("""
                update data_quality_rule set threshold = 0.1
                where tenant_id = cast(:tenant as uuid) and data_quality_rule_id = :rule
                """).param("tenant", TENANT).param("rule", registered.dataQualityRuleId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
