package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ShiftHandoverCompleteRequestWire;
import org.haoemr.contracts.ShiftHandoverCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ShiftHandoverApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String COLLABORATOR_ROLE = "018f0000-0000-7000-8000-00000000aa07";
    private static final String WARD = "018f0000-0000-7000-8000-00000000bb01";

    @Autowired
    private NursingService nursing;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private final UUID ward = UUID.fromString(WARD);

    private ClinicalIdentity outgoing() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity incoming() {
        return new ClinicalIdentity(tenant, UUID.fromString(COLLABORATOR), List.of(UUID.fromString(COLLABORATOR_ROLE)));
    }

    @Test
    void givenWard_whenCreatingAndCompletingHandover_thenLifecycleRecorded() {
        ShiftHandoverWire created = nursing.createHandover(outgoing(), "handover-" + UUID.randomUUID(),
                new ShiftHandoverCreateRequestWire(organization, facility, ward,
                        Instant.now().minusSeconds(60), Instant.now(), UUID.fromString(COLLABORATOR),
                        "交接 3 名患者：2 床待执行医嘱、5 床生命体征异常需复查、7 床压疮护理计划执行中"));
        assertThat(created.status()).isEqualTo(ShiftHandoverWire.StatusValue.DRAFT);
        assertThat(created.outgoingUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(created.incomingUserId()).isEqualTo(UUID.fromString(COLLABORATOR));

        ShiftHandoverWire completed = nursing.completeHandover(incoming(), "complete-" + UUID.randomUUID(),
                created.handoverId(), new ShiftHandoverCompleteRequestWire(
                        organization, facility, ward, created.rowVersion()));
        assertThat(completed.status()).isEqualTo(ShiftHandoverWire.StatusValue.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();

        List<ShiftHandoverWire> listed = nursing.listHandovers(outgoing(), facility, ward);
        assertThat(listed).extracting(ShiftHandoverWire::handoverId).contains(created.handoverId());
    }

    @Test
    void givenOutgoingNurse_whenCompleting_thenRejected() {
        ShiftHandoverWire created = nursing.createHandover(outgoing(), "handover-" + UUID.randomUUID(),
                new ShiftHandoverCreateRequestWire(organization, facility, ward,
                        Instant.now().minusSeconds(60), Instant.now(), UUID.fromString(COLLABORATOR),
                        "交接内容：患者夜间病情平稳，无特殊交班事项"));
        assertThatThrownBy(() -> nursing.completeHandover(outgoing(), "complete-" + UUID.randomUUID(),
                created.handoverId(), new ShiftHandoverCompleteRequestWire(
                        organization, facility, ward, created.rowVersion())))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code())
                        .isEqualTo("SHIFT_HANDOVER_INCOMING_REQUIRED"));
    }

    @Test
    void givenHandoverSummary_whenTampered_thenDatabaseRejectsMutation() {
        ShiftHandoverWire created = nursing.createHandover(outgoing(), "handover-" + UUID.randomUUID(),
                new ShiftHandoverCreateRequestWire(organization, facility, ward,
                        Instant.now().minusSeconds(60), Instant.now(), UUID.fromString(COLLABORATOR),
                        "交接内容：重点患者为 3 床术后观察"));
        assertThatThrownBy(() -> jdbc.sql("""
                update shift_handover set handover_summary = '篡改'
                where tenant_id = cast(:tenant as uuid) and handover_id = :handover
                """).param("tenant", TENANT).param("handover", created.handoverId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenDraftHandover_whenCorrecting_thenNewDraftVersionReplacesOriginal() {
        ShiftHandoverWire created = nursing.createHandover(outgoing(), "handover-" + UUID.randomUUID(),
                new ShiftHandoverCreateRequestWire(organization, facility, ward,
                        Instant.now().minusSeconds(60), Instant.now(), UUID.fromString(COLLABORATOR),
                        "交接内容：待完成风险复核"));
        ShiftHandoverWire corrected = nursing.correctHandover(outgoing(), "handover-c-" + UUID.randomUUID(),
                created.handoverId(), new ShiftHandoverCorrectionRequestWire(
                        organization, facility, ward, created.rowVersion(), Instant.now(),
                        Instant.now().plusSeconds(8 * 3600), UUID.fromString(COLLABORATOR),
                        "交接内容：已补充高危患者和未完任务", "交班摘要不完整更正"));
        assertThat(corrected.handoverId()).isNotEqualTo(created.handoverId());
        assertThat(corrected.status()).isEqualTo(ShiftHandoverWire.StatusValue.DRAFT);
        assertThat(corrected.handoverSummary()).contains("高危患者");
    }
}
