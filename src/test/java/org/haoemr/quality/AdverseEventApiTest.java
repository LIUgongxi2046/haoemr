package org.haoemr.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AdverseEventReportRequestWire;
import org.haoemr.contracts.AdverseEventReviewRequestWire;
import org.haoemr.contracts.AdverseEventWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class AdverseEventApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private AdverseEventService events;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成安全患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-AE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenActiveEncounter_whenReportingAndReviewingAdverseEvent_thenLifecycleRecorded() {
        Context context = seedContext();
        AdverseEventWire reported = events.reportEvent(identity(), "ae-" + UUID.randomUUID(),
                new AdverseEventReportRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), AdverseEventReportRequestWire.EventTypeValue.FALL,
                        AdverseEventReportRequestWire.SeverityValue.MODERATE,
                        "患者下床时跌倒，未造成骨折，已评估"));
        assertThat(reported.status()).isEqualTo(AdverseEventWire.StatusValue.REPORTED);
        assertThat(reported.severity()).isEqualTo(AdverseEventWire.SeverityValue.MODERATE);

        AdverseEventWire closed = events.reviewEvent(identity(), "review-" + UUID.randomUUID(),
                reported.adverseEventId(), new AdverseEventReviewRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        reported.rowVersion(), "已复核跌倒风险并加强床旁防护", true));
        assertThat(closed.status()).isEqualTo(AdverseEventWire.StatusValue.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.reviewConclusion()).contains("防护");

        List<AdverseEventWire> listed = events.listEvents(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(AdverseEventWire::adverseEventId).contains(reported.adverseEventId());
    }

    @Test
    void givenAdverseEventReport_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        AdverseEventWire reported = events.reportEvent(identity(), "ae-" + UUID.randomUUID(),
                new AdverseEventReportRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), AdverseEventReportRequestWire.EventTypeValue.MEDICATION_ERROR,
                        AdverseEventReportRequestWire.SeverityValue.NEAR_MISS,
                        "医嘱剂量输入错误被发现并纠正，未给药"));
        assertThatThrownBy(() -> jdbc.sql("""
                update adverse_event set severity = 'SENTINEL'
                where tenant_id = cast(:tenant as uuid) and adverse_event_id = :event
                """).param("tenant", TENANT).param("event", reported.adverseEventId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
