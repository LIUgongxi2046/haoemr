package org.haoemr.inpatient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InpatientPathwayActionRequestWire;
import org.haoemr.contracts.InpatientPathwayCatalogItemWire;
import org.haoemr.contracts.InpatientPathwayEnrollRequestWire;
import org.haoemr.contracts.InpatientPathwayInstanceWire;
import org.haoemr.contracts.InpatientPathwayStageWire;
import org.haoemr.contracts.InpatientPathwayTaskWire;
import org.haoemr.contracts.InpatientPathwayVarianceRequestWire;
import org.haoemr.contracts.InpatientPathwayVarianceReviewRequestWire;
import org.haoemr.contracts.InpatientPathwayVarianceWire;
import org.haoemr.contracts.InpatientPathwayWorkspaceWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class InpatientPathwayService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    InpatientPathwayService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    InpatientPathwayWorkspaceWire workspace(
            ClinicalIdentity identity, UUID admissionId, UUID organizationId,
            UUID facilityId, UUID patientId, UUID encounterId) {
        AdmissionContext admission = requireAdmission(identity, admissionId, organizationId, facilityId,
                patientId, encounterId, false, false);
        requireWardScope(identity, admission.facilityId(), admission.wardId());
        List<InpatientPathwayCatalogItemWire> catalog = jdbc.sql("""
                select definition.pathway_definition_id, version.pathway_version_id,
                  definition.pathway_code, definition.display_name, definition.specialty_code,
                  definition.diagnosis_code, version.version_no, version.admission_criteria,
                  version.published_at, count(distinct stage.stage_code) as stage_count,
                  count(task.task_code) as task_count
                from clinical_pathway_definition definition
                join clinical_pathway_version version on version.tenant_id=definition.tenant_id
                  and version.pathway_definition_id=definition.pathway_definition_id
                join clinical_pathway_stage stage on stage.tenant_id=version.tenant_id
                  and stage.pathway_version_id=version.pathway_version_id
                left join clinical_pathway_stage_task task on task.tenant_id=stage.tenant_id
                  and task.pathway_version_id=stage.pathway_version_id and task.stage_code=stage.stage_code
                where definition.tenant_id=:tenant and definition.status='ACTIVE'
                  and version.status='PUBLISHED'
                group by definition.pathway_definition_id, version.pathway_version_id,
                  definition.pathway_code, definition.display_name, definition.specialty_code,
                  definition.diagnosis_code, version.version_no, version.admission_criteria,
                  version.published_at
                order by definition.display_name, version.version_no desc
                """).param("tenant", identity.tenantId()).query((rs, row) ->
                new InpatientPathwayCatalogItemWire(
                        rs.getObject("pathway_definition_id", UUID.class),
                        rs.getObject("pathway_version_id", UUID.class), rs.getString("pathway_code"),
                        rs.getString("display_name"), rs.getString("specialty_code"),
                        rs.getString("diagnosis_code"), rs.getInt("version_no"),
                        rs.getString("admission_criteria"), instant(rs.getObject("published_at", OffsetDateTime.class)),
                        rs.getInt("stage_count"), rs.getInt("task_count"))).list();
        UUID instanceId = jdbc.sql("""
                select pathway_instance_id from inpatient_pathway_instance
                where tenant_id=:tenant and admission_id=:admission
                order by (status='ACTIVE') desc, enrolled_at desc limit 1
                """).param("tenant", identity.tenantId()).param("admission", admissionId)
                .query(UUID.class).optional().orElse(null);
        return new InpatientPathwayWorkspaceWire(catalog,
                instanceId == null ? null : get(identity, instanceId, patientId, encounterId));
    }

    InpatientPathwayInstanceWire enroll(
            ClinicalIdentity identity, String idempotencyKey, UUID admissionId,
            InpatientPathwayEnrollRequestWire request) {
        return transactions.execute(status -> {
            AdmissionContext admission = requireAdmission(identity, admissionId, request.organizationId(),
                    request.facilityId(), request.patientId(), request.encounterId(), true, true);
            requireWardScope(identity, admission.facilityId(), admission.wardId());
            String basis = requireText(request.admissionBasis(), 4, 2000, "admission_basis");
            PathwayTemplate template = jdbc.sql("""
                    select version.pathway_version_id, version.pathway_definition_id,
                      (select stage_code from clinical_pathway_stage
                       where tenant_id=version.tenant_id and pathway_version_id=version.pathway_version_id
                       order by sequence_no limit 1) as first_stage
                    from clinical_pathway_version version
                    join clinical_pathway_definition definition on definition.tenant_id=version.tenant_id
                      and definition.pathway_definition_id=version.pathway_definition_id
                    where version.tenant_id=:tenant and version.pathway_version_id=:version
                      and version.status='PUBLISHED' and definition.status='ACTIVE'
                    """).param("tenant", identity.tenantId()).param("version", request.pathwayVersionId())
                    .query((rs, row) -> new PathwayTemplate(
                            rs.getObject("pathway_version_id", UUID.class),
                            rs.getObject("pathway_definition_id", UUID.class), rs.getString("first_stage")))
                    .optional().orElseThrow(() -> invalid("The selected pathway version is not published"));
            long active = jdbc.sql("""
                    select count(*) from inpatient_pathway_instance
                    where tenant_id=:tenant and admission_id=:admission and status='ACTIVE'
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .query(Long.class).single();
            if (active > 0) throw conflict("PATHWAY_ALREADY_ACTIVE", "This admission already has an active pathway");
            beginCommand(identity, "INPATIENT_PATHWAY_ENROLL", idempotencyKey,
                    sha256(admissionId + "|" + request.pathwayVersionId() + "|" + basis));
            UUID instanceId = UUID.randomUUID();
            jdbc.sql("""
                    insert into inpatient_pathway_instance(
                      tenant_id, pathway_instance_id, admission_id, organization_id, facility_id,
                      patient_id, encounter_id, pathway_definition_id, pathway_version_id,
                      status, current_stage_code, admission_basis, enrolled_by)
                    values (:tenant, :instance, :admission, :organization, :facility, :patient,
                      :encounter, :definition, :version, 'ACTIVE', :stage, :basis, :actor)
                    """).param("tenant", identity.tenantId()).param("instance", instanceId)
                    .param("admission", admissionId).param("organization", request.organizationId())
                    .param("facility", request.facilityId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("definition", template.definitionId())
                    .param("version", template.versionId()).param("stage", template.firstStage())
                    .param("basis", basis).param("actor", identity.userId()).update();
            jdbc.sql("""
                    insert into inpatient_pathway_task(
                      tenant_id, pathway_task_id, pathway_instance_id, stage_code, task_code,
                      display_name, source_type, source_key, required, sequence_no, state)
                    select task.tenant_id, gen_random_uuid(), :instance, task.stage_code,
                      task.task_code, task.display_name, task.source_type, task.source_key,
                      task.required, task.sequence_no, 'PENDING'
                    from clinical_pathway_stage_task task
                    where task.tenant_id=:tenant and task.pathway_version_id=:version
                    """).param("tenant", identity.tenantId()).param("instance", instanceId)
                    .param("version", template.versionId()).update();
            appendEvidence(identity, request.patientId(), instanceId, 1,
                    "INPATIENT_PATHWAY_ENROLLED", "InpatientPathwayEnrolled");
            completeCommand(identity, "INPATIENT_PATHWAY_ENROLL", idempotencyKey, 201, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    InpatientPathwayInstanceWire refresh(
            ClinicalIdentity identity, String idempotencyKey, UUID instanceId,
            InpatientPathwayActionRequestWire request) {
        return transactions.execute(status -> {
            PathwayContext current = lock(identity, instanceId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), request.expectedRowVersion(), true);
            beginCommand(identity, "INPATIENT_PATHWAY_REFRESH", idempotencyKey,
                    sha256(instanceId + "|" + request.expectedRowVersion()));
            int changed = reconcileSourceTasks(identity, current);
            long version = current.rowVersion();
            if (changed > 0) {
                bumpInstance(identity, instanceId, version, null, null, null);
                version++;
                appendEvidence(identity, request.patientId(), instanceId, version,
                        "INPATIENT_PATHWAY_SOURCES_RECONCILED", "InpatientPathwaySourcesReconciled");
            }
            completeCommand(identity, "INPATIENT_PATHWAY_REFRESH", idempotencyKey, 200, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    InpatientPathwayInstanceWire advance(
            ClinicalIdentity identity, String idempotencyKey, UUID instanceId,
            InpatientPathwayActionRequestWire request) {
        return transactions.execute(status -> {
            PathwayContext current = lock(identity, instanceId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), request.expectedRowVersion(), true);
            beginCommand(identity, "INPATIENT_PATHWAY_ADVANCE", idempotencyKey,
                    sha256(instanceId + "|" + request.expectedRowVersion()));
            ensureCurrentStageComplete(identity, current);
            String nextStage = jdbc.sql("""
                    select next.stage_code from clinical_pathway_stage current
                    join clinical_pathway_stage next on next.tenant_id=current.tenant_id
                      and next.pathway_version_id=current.pathway_version_id
                      and next.sequence_no=current.sequence_no+1
                    where current.tenant_id=:tenant and current.pathway_version_id=:version
                      and current.stage_code=:stage
                    """).param("tenant", identity.tenantId()).param("version", current.versionId())
                    .param("stage", current.currentStage()).query(String.class).optional().orElse(null);
            if (nextStage == null) throw conflict("PATHWAY_FINAL_STAGE_REACHED",
                    "The final stage is ready; use the complete action to close the pathway");
            bumpInstance(identity, instanceId, current.rowVersion(), nextStage, null, null);
            long newVersion = current.rowVersion() + 1;
            appendEvidence(identity, request.patientId(), instanceId, newVersion,
                    "INPATIENT_PATHWAY_STAGE_ADVANCED", "InpatientPathwayStageAdvanced");
            completeCommand(identity, "INPATIENT_PATHWAY_ADVANCE", idempotencyKey, 200, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    InpatientPathwayInstanceWire complete(
            ClinicalIdentity identity, String idempotencyKey, UUID instanceId,
            InpatientPathwayActionRequestWire request) {
        return transactions.execute(status -> {
            PathwayContext current = lock(identity, instanceId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), request.expectedRowVersion(), true);
            beginCommand(identity, "INPATIENT_PATHWAY_COMPLETE", idempotencyKey,
                    sha256(instanceId + "|" + request.expectedRowVersion()));
            ensureCurrentStageComplete(identity, current);
            long remainingStages = jdbc.sql("""
                    select count(*) from clinical_pathway_stage stage
                    join clinical_pathway_stage current on current.tenant_id=stage.tenant_id
                      and current.pathway_version_id=stage.pathway_version_id
                    where stage.tenant_id=:tenant and stage.pathway_version_id=:version
                      and current.stage_code=:current and stage.sequence_no>current.sequence_no
                    """).param("tenant", identity.tenantId()).param("version", current.versionId())
                    .param("current", current.currentStage()).query(Long.class).single();
            if (remainingStages > 0) throw conflict("PATHWAY_STAGE_ADVANCE_REQUIRED",
                    "Advance through the remaining pathway stages before completion");
            bumpInstance(identity, instanceId, current.rowVersion(), null, "COMPLETED", identity.userId());
            long newVersion = current.rowVersion() + 1;
            appendEvidence(identity, request.patientId(), instanceId, newVersion,
                    "INPATIENT_PATHWAY_COMPLETED", "InpatientPathwayCompleted");
            completeCommand(identity, "INPATIENT_PATHWAY_COMPLETE", idempotencyKey, 200, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    InpatientPathwayInstanceWire requestVariance(
            ClinicalIdentity identity, String idempotencyKey, UUID instanceId,
            InpatientPathwayVarianceRequestWire request) {
        return transactions.execute(status -> {
            PathwayContext current = lock(identity, instanceId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), request.expectedRowVersion(), true);
            String reason = requireText(request.reason(), 4, 2000, "reason");
            if (request.varianceType() == null || request.disposition() == null) {
                throw invalid("variance_type and disposition are required");
            }
            boolean waiver = request.disposition() == InpatientPathwayVarianceRequestWire.DispositionValue.WAIVE_TASK;
            if (waiver != (request.affectedTaskId() != null)) {
                throw invalid("WAIVE_TASK requires exactly one affected_task_id");
            }
            if (request.affectedTaskId() != null) {
                long pending = jdbc.sql("""
                        select count(*) from inpatient_pathway_task
                        where tenant_id=:tenant and pathway_instance_id=:instance
                          and pathway_task_id=:task and state='PENDING'
                        """).param("tenant", identity.tenantId()).param("instance", instanceId)
                        .param("task", request.affectedTaskId()).query(Long.class).single();
                if (pending != 1) throw conflict("PATHWAY_TASK_NOT_WAIVABLE",
                        "Only a pending task in this pathway can be proposed for waiver");
            }
            beginCommand(identity, "INPATIENT_PATHWAY_VARIANCE_REQUEST", idempotencyKey,
                    sha256(instanceId + "|" + request.expectedRowVersion() + "|" + request.varianceType()
                            + "|" + request.disposition() + "|" + request.affectedTaskId() + "|" + reason));
            UUID varianceId = UUID.randomUUID();
            jdbc.sql("""
                    insert into inpatient_pathway_variance(
                      tenant_id, variance_id, pathway_instance_id, variance_type, reason,
                      disposition, affected_task_id, status, requested_by)
                    values (:tenant, :variance, :instance, :type, :reason, :disposition,
                      :task, 'REQUESTED', :actor)
                    """).param("tenant", identity.tenantId()).param("variance", varianceId)
                    .param("instance", instanceId).param("type", request.varianceType().name())
                    .param("reason", reason).param("disposition", request.disposition().name())
                    .param("task", request.affectedTaskId(), java.sql.Types.OTHER)
                    .param("actor", identity.userId()).update();
            bumpInstance(identity, instanceId, current.rowVersion(), null, null, null);
            long newVersion = current.rowVersion() + 1;
            appendEvidence(identity, request.patientId(), instanceId, newVersion,
                    "INPATIENT_PATHWAY_VARIANCE_REQUESTED", "InpatientPathwayVarianceRequested");
            completeCommand(identity, "INPATIENT_PATHWAY_VARIANCE_REQUEST", idempotencyKey, 201, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    InpatientPathwayInstanceWire reviewVariance(
            ClinicalIdentity identity, String idempotencyKey, UUID instanceId, UUID varianceId,
            InpatientPathwayVarianceReviewRequestWire request) {
        return transactions.execute(status -> {
            PathwayContext current = lock(identity, instanceId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), request.expectedRowVersion(), true);
            String note = requireText(request.reviewNote(), 4, 2000, "review_note");
            if (request.decision() == null) throw invalid("decision is required");
            VarianceContext variance = jdbc.sql("""
                    select requested_by, status, disposition, affected_task_id
                    from inpatient_pathway_variance
                    where tenant_id=:tenant and pathway_instance_id=:instance and variance_id=:variance
                    for update
                    """).param("tenant", identity.tenantId()).param("instance", instanceId)
                    .param("variance", varianceId).query((rs, row) -> new VarianceContext(
                            rs.getObject("requested_by", UUID.class), rs.getString("status"),
                            rs.getString("disposition"), rs.getObject("affected_task_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied("The pathway variance is not permitted"));
            if (!"REQUESTED".equals(variance.status())) throw conflict("PATHWAY_VARIANCE_ALREADY_REVIEWED",
                    "This variance already has a final review decision");
            if (identity.userId().equals(variance.requestedBy())) throw conflict(
                    "PATHWAY_VARIANCE_SELF_REVIEW_FORBIDDEN", "The requester cannot review their own variance");
            beginCommand(identity, "INPATIENT_PATHWAY_VARIANCE_REVIEW", idempotencyKey,
                    sha256(instanceId + "|" + varianceId + "|" + request.expectedRowVersion()
                            + "|" + request.decision() + "|" + note));
            String reviewStatus = request.decision() == InpatientPathwayVarianceReviewRequestWire.DecisionValue.APPROVE
                    ? "APPROVED" : "REJECTED";
            jdbc.sql("""
                    update inpatient_pathway_variance set status=:status, reviewed_by=:actor,
                      reviewed_at=now(), review_note=:note
                    where tenant_id=:tenant and variance_id=:variance and status='REQUESTED'
                    """).param("status", reviewStatus).param("actor", identity.userId()).param("note", note)
                    .param("tenant", identity.tenantId()).param("variance", varianceId).update();
            String terminalStatus = null;
            if ("APPROVED".equals(reviewStatus) && "WAIVE_TASK".equals(variance.disposition())) {
                int changed = jdbc.sql("""
                        update inpatient_pathway_task set state='WAIVED', waived_by_variance_id=:variance
                        where tenant_id=:tenant and pathway_instance_id=:instance
                          and pathway_task_id=:task and state='PENDING'
                        """).param("variance", varianceId).param("tenant", identity.tenantId())
                        .param("instance", instanceId).param("task", variance.affectedTaskId()).update();
                if (changed != 1) throw conflict("PATHWAY_TASK_NOT_WAIVABLE",
                        "The affected task is no longer pending");
            } else if ("APPROVED".equals(reviewStatus) && "EXIT_PATHWAY".equals(variance.disposition())) {
                terminalStatus = "EXITED";
            }
            if (terminalStatus == null) {
                bumpInstance(identity, instanceId, current.rowVersion(), null, null, null);
            } else {
                int changed = jdbc.sql("""
                        update inpatient_pathway_instance set status='EXITED',
                          exited_by_variance_id=:variance, row_version=row_version+1, updated_at=now()
                        where tenant_id=:tenant and pathway_instance_id=:instance
                          and row_version=:expected and status='ACTIVE'
                        """).param("variance", varianceId).param("tenant", identity.tenantId())
                        .param("instance", instanceId).param("expected", current.rowVersion()).update();
                if (changed != 1) versionConflict();
            }
            long newVersion = current.rowVersion() + 1;
            appendEvidence(identity, request.patientId(), instanceId, newVersion,
                    "INPATIENT_PATHWAY_VARIANCE_" + reviewStatus,
                    "InpatientPathwayVariance" + ("APPROVED".equals(reviewStatus) ? "Approved" : "Rejected"));
            completeCommand(identity, "INPATIENT_PATHWAY_VARIANCE_REVIEW", idempotencyKey, 200, instanceId);
            return get(identity, instanceId, request.patientId(), request.encounterId());
        });
    }

    private int reconcileSourceTasks(ClinicalIdentity identity, PathwayContext current) {
        int changed = 0;
        List<SourceTask> tasks = jdbc.sql("""
                select pathway_task_id, source_type, source_key
                from inpatient_pathway_task
                where tenant_id=:tenant and pathway_instance_id=:instance and state='PENDING'
                for update
                """).param("tenant", identity.tenantId()).param("instance", current.instanceId())
                .query((rs, row) -> new SourceTask(rs.getObject("pathway_task_id", UUID.class),
                        rs.getString("source_type"), rs.getString("source_key"))).list();
        for (SourceTask task : tasks) {
            SourceEvidence evidence;
            if ("DOCUMENT_TASK".equals(task.sourceType())) {
                evidence = jdbc.sql("""
                        select task_id, task_state, updated_at from inpatient_document_task
                        where tenant_id=:tenant and admission_id=:admission
                          and document_type_code=:source and task_state='COMPLETED'
                        order by updated_at desc limit 1
                        """).param("tenant", identity.tenantId()).param("admission", current.admissionId())
                        .param("source", task.sourceKey()).query((rs, row) -> new SourceEvidence(
                                rs.getObject("task_id", UUID.class), rs.getString("task_state"),
                                instant(rs.getObject("updated_at", OffsetDateTime.class))))
                        .optional().orElse(null);
            } else {
                evidence = jdbc.sql("""
                        select item.order_item_id, item.item_state, item.updated_at
                        from clinical_order_item item
                        join clinical_order orders on orders.tenant_id=item.tenant_id and orders.order_id=item.order_id
                        where item.tenant_id=:tenant and orders.encounter_id=:encounter
                          and item.catalog_code=:source and item.item_state='COMPLETED'
                        order by item.updated_at desc limit 1
                        """).param("tenant", identity.tenantId()).param("encounter", current.encounterId())
                        .param("source", task.sourceKey()).query((rs, row) -> new SourceEvidence(
                                rs.getObject("order_item_id", UUID.class), rs.getString("item_state"),
                                instant(rs.getObject("updated_at", OffsetDateTime.class))))
                        .optional().orElse(null);
            }
            if (evidence != null) {
                changed += jdbc.sql("""
                        update inpatient_pathway_task set state='COMPLETED', source_resource_id=:resource,
                          source_status=:source_status, completed_at=:completed
                        where tenant_id=:tenant and pathway_task_id=:task and state='PENDING'
                        """).param("resource", evidence.resourceId()).param("source_status", evidence.sourceStatus())
                        .param("completed", evidence.completedAt().atOffset(java.time.ZoneOffset.UTC))
                        .param("tenant", identity.tenantId()).param("task", task.taskId()).update();
            }
        }
        return changed;
    }

    private void ensureCurrentStageComplete(ClinicalIdentity identity, PathwayContext current) {
        long pending = jdbc.sql("""
                select count(*) from inpatient_pathway_task
                where tenant_id=:tenant and pathway_instance_id=:instance
                  and stage_code=:stage and required and state='PENDING'
                """).param("tenant", identity.tenantId()).param("instance", current.instanceId())
                .param("stage", current.currentStage()).query(Long.class).single();
        if (pending > 0) throw conflict("PATHWAY_REQUIRED_TASKS_OPEN",
                "Required pathway tasks must have real source evidence or an approved waiver");
    }

    private void bumpInstance(
            ClinicalIdentity identity, UUID instanceId, long expectedVersion,
            String nextStage, String status, UUID completedBy) {
        String sql = """
                update inpatient_pathway_instance set
                  current_stage_code=coalesce(:stage,current_stage_code),
                  status=coalesce(:status,status), completed_by=:completed_by,
                  completed_at=case when :status='COMPLETED' then now() else completed_at end,
                  row_version=row_version+1, updated_at=now()
                where tenant_id=:tenant and pathway_instance_id=:instance
                  and row_version=:expected and status='ACTIVE'
                """;
        int changed = jdbc.sql(sql).param("stage", nextStage, java.sql.Types.VARCHAR)
                .param("status", status, java.sql.Types.VARCHAR)
                .param("completed_by", completedBy, java.sql.Types.OTHER)
                .param("tenant", identity.tenantId()).param("instance", instanceId)
                .param("expected", expectedVersion).update();
        if (changed != 1) versionConflict();
    }

    private PathwayContext lock(
            ClinicalIdentity identity, UUID instanceId, UUID organizationId, UUID facilityId,
            UUID patientId, UUID encounterId, Long expectedVersion, boolean requireActive) {
        PathwayContext current = jdbc.sql("""
                select instance.pathway_instance_id, instance.admission_id, instance.pathway_version_id,
                  instance.current_stage_code, instance.status, instance.row_version,
                  instance.encounter_id, admission.ward_id, admission.facility_id,
                  admission.status as admission_status
                from inpatient_pathway_instance instance
                join inpatient_admission admission on admission.tenant_id=instance.tenant_id
                  and admission.admission_id=instance.admission_id
                where instance.tenant_id=:tenant and instance.pathway_instance_id=:instance
                  and instance.organization_id=:organization and instance.facility_id=:facility
                  and instance.patient_id=:patient and instance.encounter_id=:encounter
                for update of instance, admission
                """).param("tenant", identity.tenantId()).param("instance", instanceId)
                .param("organization", organizationId).param("facility", facilityId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new PathwayContext(
                        rs.getObject("pathway_instance_id", UUID.class),
                        rs.getObject("admission_id", UUID.class), rs.getObject("pathway_version_id", UUID.class),
                        rs.getString("current_stage_code"), rs.getString("status"), rs.getLong("row_version"),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("ward_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getString("admission_status")))
                .optional().orElseThrow(() -> contextDenied("The pathway context is not permitted"));
        requireWardScope(identity, current.facilityId(), current.wardId());
        if (expectedVersion == null || current.rowVersion() != expectedVersion) versionConflict();
        if (requireActive && !"ACTIVE".equals(current.status())) throw conflict("PATHWAY_NOT_ACTIVE",
                "Historical completed or exited pathways are read-only");
        if (requireActive && !List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING")
                .contains(current.admissionStatus())) throw conflict("ADMISSION_NOT_ACTIVE",
                "The admission is not active");
        return current;
    }

    private InpatientPathwayInstanceWire get(
            ClinicalIdentity identity, UUID instanceId, UUID patientId, UUID encounterId) {
        InstanceRow row = jdbc.sql("""
                select instance.*, definition.pathway_code, definition.display_name, version.version_no
                from inpatient_pathway_instance instance
                join clinical_pathway_definition definition on definition.tenant_id=instance.tenant_id
                  and definition.pathway_definition_id=instance.pathway_definition_id
                join clinical_pathway_version version on version.tenant_id=instance.tenant_id
                  and version.pathway_version_id=instance.pathway_version_id
                where instance.tenant_id=:tenant and instance.pathway_instance_id=:instance
                  and instance.patient_id=:patient and instance.encounter_id=:encounter
                """).param("tenant", identity.tenantId()).param("instance", instanceId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, ignored) -> new InstanceRow(
                        rs.getObject("pathway_instance_id", UUID.class), rs.getObject("admission_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("pathway_definition_id", UUID.class), rs.getObject("pathway_version_id", UUID.class),
                        rs.getString("pathway_code"), rs.getString("display_name"), rs.getInt("version_no"),
                        rs.getString("status"), rs.getString("current_stage_code"), rs.getString("admission_basis"),
                        rs.getObject("enrolled_by", UUID.class), instant(rs.getObject("enrolled_at", OffsetDateTime.class)),
                        rs.getObject("completed_by", UUID.class), instant(rs.getObject("completed_at", OffsetDateTime.class)),
                        rs.getObject("exited_by_variance_id", UUID.class), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied("The pathway context is not permitted"));
        int currentSequence = jdbc.sql("""
                select sequence_no from clinical_pathway_stage
                where tenant_id=:tenant and pathway_version_id=:version and stage_code=:stage
                """).param("tenant", identity.tenantId()).param("version", row.versionId())
                .param("stage", row.currentStage()).query(Integer.class).single();
        List<InpatientPathwayStageWire> stages = jdbc.sql("""
                select stage_code, display_name, sequence_no, expected_day_start, expected_day_end
                from clinical_pathway_stage where tenant_id=:tenant and pathway_version_id=:version
                order by sequence_no
                """).param("tenant", identity.tenantId()).param("version", row.versionId())
                .query((rs, ignored) -> new StageRow(rs.getString("stage_code"), rs.getString("display_name"),
                        rs.getInt("sequence_no"), rs.getInt("expected_day_start"), rs.getInt("expected_day_end")))
                .list().stream().map(stage -> mapStage(identity, row, stage, currentSequence)).toList();
        List<InpatientPathwayVarianceWire> variances = jdbc.sql("""
                select * from inpatient_pathway_variance
                where tenant_id=:tenant and pathway_instance_id=:instance
                order by requested_at desc, variance_id desc
                """).param("tenant", identity.tenantId()).param("instance", instanceId)
                .query((rs, ignored) -> new InpatientPathwayVarianceWire(
                        rs.getObject("variance_id", UUID.class),
                        InpatientPathwayVarianceWire.VarianceTypeValue.valueOf(rs.getString("variance_type")),
                        rs.getString("reason"),
                        InpatientPathwayVarianceWire.DispositionValue.valueOf(rs.getString("disposition")),
                        rs.getObject("affected_task_id", UUID.class),
                        InpatientPathwayVarianceWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("requested_by", UUID.class), instant(rs.getObject("requested_at", OffsetDateTime.class)),
                        rs.getObject("reviewed_by", UUID.class), instant(rs.getObject("reviewed_at", OffsetDateTime.class)),
                        rs.getString("review_note"))).list();
        int required = stages.stream().mapToInt(InpatientPathwayStageWire::requiredTaskCount).sum();
        int completed = stages.stream().mapToInt(InpatientPathwayStageWire::completedTaskCount).sum();
        int percent = required == 0 ? 100 : (int) Math.round(completed * 100.0 / required);
        String watermark = sha256(row.instanceId() + "|" + row.status() + "|" + row.rowVersion()
                + "|" + completed + "|" + variances.size());
        return new InpatientPathwayInstanceWire(
                row.instanceId(), row.admissionId(), row.patientId(), row.encounterId(),
                row.definitionId(), row.versionId(), row.pathwayCode(), row.displayName(), row.versionNo(),
                InpatientPathwayInstanceWire.StatusValue.valueOf(row.status()), row.currentStage(),
                row.admissionBasis(), row.enrolledBy(), row.enrolledAt(), row.completedBy(), row.completedAt(),
                row.exitedByVarianceId(), row.rowVersion(), required, completed, percent, stages, variances, watermark);
    }

    private InpatientPathwayStageWire mapStage(
            ClinicalIdentity identity, InstanceRow instance, StageRow stage, int currentSequence) {
        List<InpatientPathwayTaskWire> tasks = jdbc.sql("""
                select * from inpatient_pathway_task
                where tenant_id=:tenant and pathway_instance_id=:instance and stage_code=:stage
                order by sequence_no, task_code
                """).param("tenant", identity.tenantId()).param("instance", instance.instanceId())
                .param("stage", stage.code()).query((rs, ignored) -> new InpatientPathwayTaskWire(
                        rs.getObject("pathway_task_id", UUID.class), rs.getString("stage_code"),
                        rs.getString("task_code"), rs.getString("display_name"),
                        InpatientPathwayTaskWire.SourceTypeValue.valueOf(rs.getString("source_type")),
                        rs.getString("source_key"), rs.getBoolean("required"),
                        InpatientPathwayTaskWire.StateValue.valueOf(rs.getString("state")),
                        rs.getObject("source_resource_id", UUID.class), rs.getString("source_status"),
                        instant(rs.getObject("completed_at", OffsetDateTime.class)),
                        rs.getObject("waived_by_variance_id", UUID.class))).list();
        int required = (int) tasks.stream().filter(InpatientPathwayTaskWire::required).count();
        int completed = (int) tasks.stream().filter(task -> task.required()
                && task.state() != InpatientPathwayTaskWire.StateValue.PENDING).count();
        InpatientPathwayStageWire.StatusValue status;
        if ("COMPLETED".equals(instance.status()) || stage.sequence() < currentSequence) {
            status = InpatientPathwayStageWire.StatusValue.COMPLETED;
        } else if (stage.sequence() == currentSequence) {
            status = InpatientPathwayStageWire.StatusValue.CURRENT;
        } else {
            status = InpatientPathwayStageWire.StatusValue.UPCOMING;
        }
        return new InpatientPathwayStageWire(stage.code(), stage.displayName(), stage.sequence(),
                stage.dayStart(), stage.dayEnd(), status, required, completed, tasks);
    }

    private AdmissionContext requireAdmission(
            ClinicalIdentity identity, UUID admissionId, UUID organizationId, UUID facilityId,
            UUID patientId, UUID encounterId, boolean lock, boolean requireActive) {
        String sql = """
                select admission.facility_id, admission.ward_id, admission.status
                from inpatient_admission admission
                join encounter on encounter.tenant_id=admission.tenant_id
                  and encounter.encounter_id=admission.encounter_id
                where admission.tenant_id=:tenant and admission.admission_id=:admission
                  and encounter.organization_id=:organization and admission.facility_id=:facility
                  and admission.patient_id=:patient and admission.encounter_id=:encounter
                """ + (lock ? " for update of admission" : "");
        AdmissionContext admission = jdbc.sql(sql).param("tenant", identity.tenantId())
                .param("admission", admissionId).param("organization", organizationId)
                .param("facility", facilityId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new AdmissionContext(rs.getObject("facility_id", UUID.class),
                        rs.getObject("ward_id", UUID.class), rs.getString("status")))
                .optional().orElseThrow(() -> contextDenied("The admission context is not permitted"));
        if (requireActive && !List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING")
                .contains(admission.status())) throw conflict("ADMISSION_NOT_ACTIVE", "The admission is not active");
        return admission;
    }

    private void requireWardScope(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        String roles = "{" + identity.roleAssignmentIds().stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        long count = jdbc.sql("""
                select count(*) from ward_role_scope scope
                join clinical_ward ward on ward.tenant_id=scope.tenant_id and ward.ward_id=scope.ward_id
                join role_assignment assignment on assignment.tenant_id=scope.tenant_id
                  and assignment.role_assignment_id=scope.role_assignment_id
                where scope.tenant_id=:tenant and scope.ward_id=:ward and ward.facility_id=:facility
                  and scope.role_assignment_id=any(cast(:roles as uuid[]))
                  and scope.valid_from<=now() and (scope.valid_until is null or scope.valid_until>now())
                  and assignment.user_id=:user and assignment.status='ACTIVE'
                  and assignment.valid_from<=now() and (assignment.valid_until is null or assignment.valid_until>now())
                """).param("tenant", identity.tenantId()).param("ward", wardId).param("facility", facilityId)
                .param("roles", roles).param("user", identity.userId()).query(Long.class).single();
        if (count < 1) throw new InpatientException("WARD_SCOPE_DENIED", 403,
                "The current role has no active scope for this ward");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id, command_scope, idempotency_key, request_hash,
                  state, trace_id, expires_at)
                values (:tenant,:scope,:key,:hash,'IN_PROGRESS',:trace,now()+interval '24 hours')
                on conflict (tenant_id,command_scope,idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw conflict("IDEMPOTENCY_REPLAY", "This pathway command key was already used");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state='SUCCEEDED', response_status=:status,
                  response_ref=jsonb_build_object('resource_id',:resource)
                where tenant_id=:tenant and command_scope=:scope and idempotency_key=:key
                """).param("status", responseStatus).param("resource", resourceId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID instanceId, long aggregateVersion,
            String actionCode, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id=:tenant
                order by occurred_at desc,audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + instanceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,
                  resource_type,resource_id,patient_ref_hash,trace_id,previous_hash,event_hash,details)
                values (:tenant,:audit,now(),:actor,:action,'INPATIENT_PATHWAY',:instance,
                  :patient_hash,:trace,:previous,:event_hash,jsonb_build_object('aggregate_version',:version))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", actionCode).param("instance", instanceId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash, java.sql.Types.VARCHAR).param("event_hash", eventHash)
                .param("version", aggregateVersion).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,
                  event_type,schema_version,payload)
                values (:tenant,:event,'INPATIENT_PATHWAY',:instance,:version,:event_type,1,
                  jsonb_build_object('pathway_instance_id',:instance))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("instance", instanceId).param("version", aggregateVersion)
                .param("event_type", eventType).update();
    }

    private static String requireText(String value, int min, int max, String field) {
        if (value == null || value.trim().length() < min || value.trim().length() > max) {
            throw invalid(field + " must contain " + min + " to " + max + " characters");
        }
        return value.trim();
    }

    private static InpatientException invalid(String message) {
        return new InpatientException("PATHWAY_VALIDATION_FAILED", 400, message);
    }

    private static InpatientException conflict(String code, String message) {
        return new InpatientException(code, 409, message);
    }

    private static InpatientException contextDenied(String message) {
        return new InpatientException("CONTEXT_NOT_PERMITTED", 403, message);
    }

    private static void versionConflict() {
        throw conflict("PATHWAY_VERSION_CONFLICT", "The pathway changed; reload before retrying");
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record AdmissionContext(UUID facilityId, UUID wardId, String status) {}
    private record PathwayTemplate(UUID versionId, UUID definitionId, String firstStage) {}
    private record SourceTask(UUID taskId, String sourceType, String sourceKey) {}
    private record SourceEvidence(UUID resourceId, String sourceStatus, Instant completedAt) {}
    private record VarianceContext(UUID requestedBy, String status, String disposition, UUID affectedTaskId) {}
    private record StageRow(String code, String displayName, int sequence, int dayStart, int dayEnd) {}
    private record PathwayContext(
            UUID instanceId, UUID admissionId, UUID versionId, String currentStage, String status,
            long rowVersion, UUID encounterId, UUID wardId, UUID facilityId, String admissionStatus) {
    }
    private record InstanceRow(
            UUID instanceId, UUID admissionId, UUID patientId, UUID encounterId,
            UUID definitionId, UUID versionId, String pathwayCode, String displayName,
            int versionNo, String status, String currentStage, String admissionBasis,
            UUID enrolledBy, Instant enrolledAt, UUID completedBy, Instant completedAt,
            UUID exitedByVarianceId, long rowVersion) {}
}
