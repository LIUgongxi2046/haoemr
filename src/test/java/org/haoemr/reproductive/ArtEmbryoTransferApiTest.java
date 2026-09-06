package org.haoemr.reproductive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtEmbryoTransferRecordCreateRequestWire;
import org.haoemr.contracts.ArtEmbryoTransferRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ArtEmbryoTransferApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private ArtEmbryoTransferService transfers;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext(LocalDate ethicsConsentDate) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成ART患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1990, 11, 11)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ART', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into art_cycle_record(
                  tenant_id, cycle_id, patient_id, encounter_id, facility_id,
                  cycle_type, cycle_number, ethics_consent_date, status)
                values (cast(:tenant as uuid), :cycle, :patient, :encounter, cast(:facility as uuid),
                  'IVF', 1, :consent, 'ACTIVE')
                """).param("tenant", TENANT).param("cycle", cycleId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY)
                .param("consent", ethicsConsentDate).update();
        return new Context(patientId, cycleId);
    }

    private ArtEmbryoTransferRecordWire record(Context context, int embryoCount, UUID verifierId, Instant transferredAt) {
        return transfers.record(identity(), "transfer-" + UUID.randomUUID(),
                new ArtEmbryoTransferRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.cycleId(), embryoCount, verifierId, transferredAt));
    }

    @Test
    void givenConsentedCycle_whenRecording_thenRecorded() {
        Context context = seedContext(LocalDate.now().minusDays(30));
        ArtEmbryoTransferRecordWire recorded = record(context, 1, UUID.fromString(COLLABORATOR), Instant.now());
        assertThat(recorded.embryoCount()).isEqualTo(1);
        assertThat(recorded.operatorId()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.verifierId()).isEqualTo(UUID.fromString(COLLABORATOR));

        List<ArtEmbryoTransferRecordWire> listed = transfers.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(ArtEmbryoTransferRecordWire::embryoTransferId)
                .contains(recorded.embryoTransferId());
    }

    @Test
    void givenTransferBeforeConsent_whenRecording_thenRejected() {
        Context context = seedContext(LocalDate.now().minusDays(10));
        assertThatThrownBy(() -> record(context, 1, UUID.fromString(COLLABORATOR),
                Instant.now().minus(20, java.time.temporal.ChronoUnit.DAYS)))
                .isInstanceOf(ArtEmbryoTransferException.class)
                .satisfies(e -> assertThat(((ArtEmbryoTransferException) e).code())
                        .isEqualTo("ETHICS_CONSENT_REQUIRED"));
    }

    @Test
    void givenSelfVerifier_whenRecording_thenRejected() {
        Context context = seedContext(LocalDate.now().minusDays(30));
        assertThatThrownBy(() -> record(context, 1, UUID.fromString(USER), Instant.now()))
                .isInstanceOf(ArtEmbryoTransferException.class)
                .satisfies(e -> assertThat(((ArtEmbryoTransferException) e).code())
                        .isEqualTo("SELF_VERIFICATION_FORBIDDEN"));
    }

    @Test
    void givenZeroEmbryoCount_whenRecording_thenRejected() {
        Context context = seedContext(LocalDate.now().minusDays(30));
        assertThatThrownBy(() -> record(context, 0, UUID.fromString(COLLABORATOR), Instant.now()))
                .isInstanceOf(ArtEmbryoTransferException.class)
                .satisfies(e -> assertThat(((ArtEmbryoTransferException) e).code())
                        .isEqualTo("ART_EMBRYO_TRANSFER_REQUEST_INVALID"));
    }

    @Test
    void givenTransfer_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext(LocalDate.now().minusDays(30));
        ArtEmbryoTransferRecordWire recorded = record(context, 1, UUID.fromString(COLLABORATOR), Instant.now());
        assertThatThrownBy(() -> jdbc.sql("""
                update art_embryo_transfer_record set embryo_count = 5
                where tenant_id = cast(:tenant as uuid) and embryo_transfer_id = :transfer
                """).param("tenant", TENANT).param("transfer", recorded.embryoTransferId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID cycleId) {}
}
