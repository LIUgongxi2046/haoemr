package org.haoemr.administration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.administration.AdministrationRuntimeService.GovernanceFindingWire;
import org.haoemr.administration.AdministrationRuntimeService.JobRunWire;
import org.haoemr.administration.MasterDataRecordService.DeactivateRequest;
import org.haoemr.administration.MasterDataRecordService.MasterDataCreateRequest;
import org.haoemr.administration.MasterDataRecordService.MasterDataRecordWire;
import org.haoemr.administration.MasterDataRecordService.MasterDataUpdateRequest;
import org.haoemr.administration.WorkgroupAdministrationService.EndRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupCreateRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupMemberCreateRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
@Rollback
final class SystemAdministrationRuntimeApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ADMIN_USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID DIAGNOSIS_CATALOG = UUID.fromString("018f0000-0000-7000-8000-00000000c611");
    private static final UUID GOVERNANCE_JOB = UUID.fromString("018f0000-0000-7000-8000-00000000c621");
    private static final UUID GOVERNANCE_RUN = UUID.fromString("018f0000-0000-7000-8000-00000000c631");

    @Autowired private MasterDataRecordService masterData;
    @Autowired private WorkgroupAdministrationService workgroups;
    @Autowired private AdministrationRuntimeService runtime;
    @Autowired private JdbcClient jdbc;

    private ClinicalIdentity administrator() {
        return new ClinicalIdentity(TENANT, ADMIN_USER, List.of(ADMIN_ROLE));
    }

    @Test
    void masterDataCreateUpdateAndDeactivatePersistWithVersions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant effectiveFrom = Instant.parse("2026-08-31T00:00:00Z");
        MasterDataRecordWire created = masterData.create(administrator(), "test-master-create-" + suffix,
                new MasterDataCreateRequest(DIAGNOSIS_CATALOG, "NHSA-DIAGNOSIS-2.0", "Z00.000",
                        "DX-QC-" + suffix, "一般医学检查 / General medical examination",
                        "诊断>影响健康状态的因素>医学检查", "医保版2.0", "国家医疗保障局",
                        "MATCHED", effectiveFrom, null, Map.of("maintained_by", "病案统计室")));
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.rowVersion()).isEqualTo(1);

        MasterDataRecordWire updated = masterData.update(administrator(), created.recordId(),
                "test-master-update-" + suffix,
                new MasterDataUpdateRequest(created.rowVersion(), DIAGNOSIS_CATALOG,
                        "NHSA-DIAGNOSIS-2.0", "Z00.000", created.localCode(),
                        "一般医学检查（病案复核） / General medical examination",
                        created.categoryPath(), "医保版2.0", "国家医疗保障局", "MATCHED",
                        effectiveFrom, null, Map.of("maintained_by", "病案统计室", "reviewed", true)));
        assertThat(updated.rowVersion()).isEqualTo(2);
        assertThat(updated.displayName()).contains("病案复核");

        MasterDataRecordWire inactive = masterData.deactivate(administrator(), created.recordId(),
                "test-master-deactivate-" + suffix,
                new DeactivateRequest(updated.rowVersion(), "测试确认停用并保留历史临床引用"));
        assertThat(inactive.status()).isEqualTo("INACTIVE");
        assertThat(inactive.rowVersion()).isEqualTo(3);
        assertThat(masterData.list(administrator(), DIAGNOSIS_CATALOG, created.localCode(), "INACTIVE"))
                .extracting(MasterDataRecordWire::recordId).containsExactly(created.recordId());
    }

    @Test
    void workgroupMemberLifecycleBlocksOrphanedActiveMembership() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID owner = jdbc.sql("select person_id from workforce_person where tenant_id = :tenant and person_code = 'JC-AD-5001'")
                .param("tenant", TENANT).query(UUID.class).single();
        UUID memberPerson = jdbc.sql("select person_id from workforce_person where tenant_id = :tenant and person_code = 'JC-IT-5003'")
                .param("tenant", TENANT).query(UUID.class).single();
        UUID workgroupId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        WorkgroupWire created = workgroups.create(administrator(), "test-workgroup-create-" + suffix,
                new WorkgroupCreateRequest(workgroupId, "WG-MR-QC-" + suffix,
                        "病案质量复核组 / Medical Record Quality Review", "病案编码、归档与质量问题复核",
                        ORGANIZATION, FACILITY, null, owner, Instant.now().minusSeconds(60), null));
        assertThat(created.status()).isEqualTo("ACTIVE");

        WorkgroupWire withMember = workgroups.addMember(administrator(), workgroupId,
                "test-workgroup-member-" + suffix,
                new WorkgroupMemberCreateRequest(memberId, memberPerson, "SECURITY_AUDITOR",
                        "复核审计证据与高风险变更", Instant.now().minusSeconds(30), null));
        assertThat(withMember.members()).singleElement().satisfies(member -> {
            assertThat(member.status()).isEqualTo("ACTIVE");
            assertThat(member.roleCode()).isEqualTo("SECURITY_AUDITOR");
        });

        long memberVersion = withMember.members().getFirst().rowVersion();
        WorkgroupWire withoutMember = workgroups.endMember(administrator(), workgroupId, memberId,
                "test-workgroup-member-end-" + suffix, new EndRequest(memberVersion, "岗位轮换结束"));
        assertThat(withoutMember.members()).singleElement().extracting(member -> member.status())
                .isEqualTo("INACTIVE");
        WorkgroupWire inactive = workgroups.deactivate(administrator(), workgroupId,
                "test-workgroup-deactivate-" + suffix,
                new EndRequest(withoutMember.rowVersion(), "专项复核任务完成"));
        assertThat(inactive.status()).isEqualTo("INACTIVE");
    }

    @Test
    void jobQueueCancelRetryAndFindingResolutionAreDatabaseBacked() {
        String suffix = UUID.randomUUID().toString();
        JobRunWire queued = runtime.start(administrator(), GOVERNANCE_JOB, "test-admin-job-start-" + suffix);
        assertThat(queued.status()).isEqualTo("QUEUED");
        JobRunWire cancelled = runtime.cancel(administrator(), queued.runId(), queued.rowVersion());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        JobRunWire retried = runtime.retry(administrator(), cancelled.runId(), "test-admin-job-retry-" + suffix);
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.runId()).isNotEqualTo(cancelled.runId());

        GovernanceFindingWire open = runtime.listFindings(administrator(), GOVERNANCE_RUN).stream()
                .filter(finding -> "OPEN".equals(finding.status())).findFirst().orElseThrow();
        GovernanceFindingWire resolved = runtime.resolve(administrator(), open.findingId(), open.rowVersion(),
                "检验科与主数据管理组已完成平台、单位和参考区间复核");
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy()).isEqualTo(ADMIN_USER);
        assertThat(resolved.evidence()).containsKey("resolution");
    }
}
