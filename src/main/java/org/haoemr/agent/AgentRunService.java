package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.AIProposalWire;
import org.haoemr.contracts.AIRunSnapshotWire;
import org.haoemr.contracts.ContextReferenceWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
final class AgentRunService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final List<ClinicalModelProvider> providers;
    private final AgentOutputGuard outputGuard;

    AgentRunService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            List<ClinicalModelProvider> providers,
            AgentOutputGuard outputGuard) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.providers = List.copyOf(providers);
        this.outputGuard = outputGuard;
    }

    AIRunSnapshotWire createAndRun(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID contextLeaseId,
            String useCaseCode,
            UUID documentId,
            UUID documentVersionId,
            UUID patientId,
            UUID encounterId) {
        UUID runId = transactions.execute(status -> createRun(
                identity, idempotencyKey, contextLeaseId, useCaseCode, documentId,
                documentVersionId, patientId, encounterId));
        executeRun(identity.tenantId(), runId);
        return snapshot(identity.tenantId(), runId);
    }

    AIRunSnapshotWire snapshot(UUID tenantId, UUID runId) {
        RunRow run = run(tenantId, runId);
        List<AIProposalWire> proposals = jdbc.sql("""
                select proposal_id, proposal_type, status, payload::text, context_references::text,
                  expires_at, row_version
                from ai_proposal where tenant_id = :tenant and run_id = :run
                order by created_at, proposal_id
                """)
                .param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new AIProposalWire(
                        rs.getObject("proposal_id", UUID.class),
                        runId,
                        rs.getString("proposal_type"),
                        AIProposalWire.StatusValue.valueOf(rs.getString("status")),
                        map(rs.getString("payload")),
                        references(rs.getString("context_references")),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .list();
        return new AIRunSnapshotWire(
                run.runId(), run.contextLeaseId(), AIRunSnapshotWire.StateValue.valueOf(run.state()),
                run.sequence(), run.dataWatermark(), run.createdAt().toInstant(), run.updatedAt().toInstant(), proposals);
    }

    List<AIRunSnapshotWire> listRuns(UUID tenantId) {
        return jdbc.sql("""
                select run_id, context_lease_id, state, sequence, data_watermark, created_at, updated_at
                from ai_run where tenant_id = :tenant order by created_at desc, run_id desc limit 500
                """).param("tenant", tenantId)
                .query((rs, row) -> new AIRunSnapshotWire(
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("context_lease_id", UUID.class),
                        AIRunSnapshotWire.StateValue.valueOf(rs.getString("state")),
                        rs.getLong("sequence"),
                        rs.getString("data_watermark"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        List.of()))
                .list();
    }

    RunContext context(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                select document.patient_id, document.encounter_id, encounter.organization_id, encounter.facility_id
                from ai_run run
                join clinical_document document
                  on document.tenant_id = run.tenant_id and document.document_id = run.document_id
                join encounter on encounter.tenant_id = document.tenant_id and encounter.encounter_id = document.encounter_id
                where run.tenant_id = :tenant and run.run_id = :run
                """)
                .param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RunContext(
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                .optional().orElseThrow(AgentRunService::contextDenied);
    }

    RunContext proposalContext(UUID tenantId, UUID proposalId) {
        return jdbc.sql("""
                select document.patient_id, document.encounter_id, encounter.organization_id, encounter.facility_id
                from ai_proposal proposal
                join ai_run run on run.tenant_id = proposal.tenant_id and run.run_id = proposal.run_id
                join clinical_document document
                  on document.tenant_id = run.tenant_id and document.document_id = run.document_id
                join encounter on encounter.tenant_id = document.tenant_id and encounter.encounter_id = document.encounter_id
                where proposal.tenant_id = :tenant and proposal.proposal_id = :proposal
                """)
                .param("tenant", tenantId).param("proposal", proposalId)
                .query((rs, row) -> new RunContext(
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                .optional().orElseThrow(AgentRunService::contextDenied);
    }

    String events(UUID tenantId, UUID runId, long afterSequence) {
        run(tenantId, runId);
        StringBuilder sse = new StringBuilder();
        jdbc.sql("""
                select sequence, event_type, payload::text from ai_run_event
                where tenant_id = :tenant and run_id = :run and sequence > :after
                order by sequence
                """)
                .param("tenant", tenantId).param("run", runId).param("after", Math.max(0, afterSequence))
                .query((rs, row) -> new EventRow(
                        rs.getLong("sequence"), rs.getString("event_type"), rs.getString("payload")))
                .list().forEach(event -> sse.append("id: ").append(event.sequence()).append('\n')
                        .append("event: ").append(event.eventType()).append('\n')
                        .append("data: ").append(event.payload()).append("\n\n"));
        return sse.toString();
    }

    AIProposalWire decide(
            ClinicalIdentity identity,
            UUID proposalId,
            long expectedRowVersion,
            String decision,
            String reason) {
        return transactions.execute(status -> {
            ProposalForDecision proposal = jdbc.sql("""
                    select proposal.run_id, proposal.status, proposal.payload::text,
                      proposal.context_references::text, proposal.expires_at, proposal.row_version,
                      proposal.proposal_type
                    from ai_proposal proposal
                    where proposal.tenant_id = :tenant and proposal.proposal_id = :proposal
                    for update
                    """)
                    .param("tenant", identity.tenantId()).param("proposal", proposalId)
                    .query((rs, row) -> new ProposalForDecision(
                            rs.getObject("run_id", UUID.class), rs.getString("status"), rs.getString("payload"),
                            rs.getString("context_references"), rs.getObject("expires_at", OffsetDateTime.class),
                            rs.getLong("row_version"), rs.getString("proposal_type")))
                    .optional().orElseThrow(AgentRunService::contextDenied);
            if (!"PENDING_REVIEW".equals(proposal.status()) || proposal.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw new AgentRunException("APPROVAL_EXPIRED", 409, "The AI proposal is no longer reviewable");
            }
            if (proposal.rowVersion() != expectedRowVersion) {
                throw new AgentRunException("VERSION_CONFLICT", 409, "The AI proposal changed before the decision");
            }
            AIProposalWire.StatusValue decisionValue;
            try {
                decisionValue = AIProposalWire.StatusValue.valueOf(decision);
            } catch (IllegalArgumentException invalid) {
                throw new AgentRunException("INVALID_AI_DECISION", 400, "Unsupported AI proposal decision");
            }
            if (decisionValue == AIProposalWire.StatusValue.PENDING_REVIEW
                    || decisionValue == AIProposalWire.StatusValue.EXPIRED) {
                throw new AgentRunException("INVALID_AI_DECISION", 400, "Unsupported AI proposal decision");
            }
            OffsetDateTime decidedAt = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.sql("""
                    update ai_proposal set status = :decision, decided_by = :user, decided_at = :decided,
                      decision_reason = :reason, row_version = row_version + 1
                    where tenant_id = :tenant and proposal_id = :proposal and row_version = :expected
                    """)
                    .param("decision", decisionValue.name()).param("user", identity.userId()).param("decided", decidedAt)
                    .param("reason", reason).param("tenant", identity.tenantId()).param("proposal", proposalId)
                    .param("expected", expectedRowVersion).update();
            String runState = decisionValue == AIProposalWire.StatusValue.REJECTED ? "REJECTED" : "ACCEPTED";
            transition(identity.tenantId(), proposal.runId(), runState, "ProposalDecisionRecorded",
                    Map.of("proposal_id", proposalId.toString(), "decision", decisionValue.name()));
            appendDecisionEvidence(identity, proposalId, proposal.runId(), decisionValue.name());
            return new AIProposalWire(
                    proposalId, proposal.runId(), proposal.proposalType(), decisionValue,
                    map(proposal.payload()), references(proposal.references()), proposal.expiresAt().toInstant(),
                    expectedRowVersion + 1);
        });
    }

    private UUID createRun(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID contextLeaseId,
            String useCaseCode,
            UUID documentId,
            UUID documentVersionId,
            UUID patientId,
            UUID encounterId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AgentRunException("VALIDATION_FAILED", 400, "Idempotency-Key is required");
        }
        Policy policy = jdbc.sql("""
                select enabled, provider_code, model_code, model_residency_policy, prompt_version
                from ai_use_case_policy where tenant_id = :tenant and use_case_code = :use_case
                """)
                .param("tenant", identity.tenantId()).param("use_case", useCaseCode)
                .query((rs, row) -> new Policy(
                        rs.getBoolean("enabled"), rs.getString("provider_code"), rs.getString("model_code"),
                        rs.getString("model_residency_policy"), rs.getString("prompt_version")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "AI_USE_CASE_DISABLED", 409, "This AI use case is disabled"));
        if (!policy.enabled()) {
            throw new AgentRunException("AI_USE_CASE_DISABLED", 409, "This AI use case is disabled");
        }
        SourceRow source = source(identity.tenantId(), contextLeaseId, documentId, documentVersionId, patientId, encounterId);
        if (!policy.residency().equals(source.residency())) {
            throw new AgentRunException("MODEL_RESIDENCY_DENIED", 403, "The selected model does not satisfy the lease residency policy");
        }
        String parameterHash = sha256(contextLeaseId + "|" + useCaseCode + "|" + documentVersionId + "|" + source.contentHash());
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'AI_RUN_CREATE', :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """)
                .param("tenant", identity.tenantId()).param("key", idempotencyKey).param("hash", parameterHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AgentRunException("IDEMPOTENCY_REPLAY", 409, "This AI run command has already been used");
        }
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                insert into ai_run(
                  tenant_id, run_id, context_lease_id, use_case_code, document_id, document_version_id,
                  state, parameter_hash, data_watermark, provider_code, model_code, prompt_version,
                  max_tool_calls, max_output_tokens, deadline_at)
                values (:tenant, :run, :lease, :use_case, :document, :version,
                  'CREATED', :parameter_hash, :watermark, :provider, :model, :prompt,
                  4, 2048, now() + interval '30 seconds')
                """)
                .param("tenant", identity.tenantId()).param("run", runId).param("lease", contextLeaseId)
                .param("use_case", useCaseCode).param("document", documentId).param("version", documentVersionId)
                .param("parameter_hash", parameterHash).param("watermark", source.contentHash())
                .param("provider", policy.provider()).param("model", policy.model()).param("prompt", policy.prompt()).update();
        transition(identity.tenantId(), runId, "CREATED", "RunCreated", Map.of("run_id", runId.toString()));
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 202,
                  response_ref = jsonb_build_object('run_id', :run)
                where tenant_id = :tenant and command_scope = 'AI_RUN_CREATE' and idempotency_key = :key
                """)
                .param("run", runId).param("tenant", identity.tenantId()).param("key", idempotencyKey).update();
        return runId;
    }

    private void executeRun(UUID tenantId, UUID runId) {
        transactions.executeWithoutResult(status -> {
            RunRow run = runForUpdate(tenantId, runId);
            if (run.deadlineAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                transition(tenantId, runId, "EXPIRED", "RunExpired", Map.of("code", "DEADLINE_EXCEEDED"));
                return;
            }
            SourceRow source = source(tenantId, run.contextLeaseId(), run.documentId(), run.documentVersionId(), null, null);
            transition(tenantId, runId, "RETRIEVING", "ContextRetrievalStarted", Map.of());
            jdbc.sql("""
                    insert into ai_tool_invocation(
                      tenant_id, invocation_id, run_id, tool_code, source_type, source_id,
                      source_version, authorization_watermark, outcome)
                    values (:tenant, :invocation, :run, 'READ_DOCUMENT_VERSION', 'DOCUMENT_VERSION',
                      :source, :source_version, :watermark, 'ALLOWED')
                    """)
                    .param("tenant", tenantId).param("invocation", UUID.randomUUID()).param("run", runId)
                    .param("source", run.documentVersionId()).param("source_version", run.documentVersionId().toString())
                    .param("watermark", source.authorizationWatermark()).update();
            jdbc.sql("update ai_run set tool_call_count = tool_call_count + 1 where tenant_id = :tenant and run_id = :run")
                    .param("tenant", tenantId).param("run", runId).update();
            transition(tenantId, runId, "GENERATING", "GenerationStarted", Map.of("provider", run.providerCode()));
            ClinicalModelProvider provider = providers.stream().filter(item -> item.supports(run.providerCode()))
                    .findFirst().orElse(null);
            if (provider == null) {
                jdbc.sql("update ai_run set error_code = 'MODEL_PROVIDER_UNAVAILABLE' where tenant_id = :tenant and run_id = :run")
                        .param("tenant", tenantId).param("run", runId).update();
                transition(tenantId, runId, "DEGRADED", "RunDegraded", Map.of("code", "MODEL_PROVIDER_UNAVAILABLE"));
                return;
            }
            Map<String, Object> payload;
            try {
                payload = provider.generate(
                        new ClinicalModelProvider.DraftPrompt(tenantId, run.providerCode(), run.modelCode(),
                                parse(source.sectionsJson()), run.maxOutputTokens()));
            } catch (ModelProviderUnavailableException unavailable) {
                jdbc.sql("update ai_run set error_code = :code where tenant_id = :tenant and run_id = :run")
                        .param("code", unavailable.code()).param("tenant", tenantId).param("run", runId).update();
                transition(tenantId, runId, "DEGRADED", "RunDegraded", Map.of("code", unavailable.code()));
                return;
            }
            outputGuard.validate(payload);
            ContextReferenceWire reference = reference(run, source);
            UUID proposalId = UUID.randomUUID();
            OffsetDateTime expiry = source.leaseExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30))
                    ? source.leaseExpiresAt() : OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);
            jdbc.sql("""
                    insert into ai_proposal(
                      tenant_id, proposal_id, run_id, proposal_type, status, payload,
                      context_references, authorization_watermark, expires_at)
                    values (:tenant, :proposal, :run, 'DOCUMENT_DRAFT', 'PENDING_REVIEW',
                      cast(:payload as jsonb), cast(:references as jsonb), :watermark, :expires)
                    """)
                    .param("tenant", tenantId).param("proposal", proposalId).param("run", runId)
                    .param("payload", json(payload)).param("references", json(List.of(reference)))
                    .param("watermark", source.authorizationWatermark()).param("expires", expiry).update();
            transition(tenantId, runId, "READY_FOR_REVIEW", "ProposalReady",
                    Map.of("proposal_id", proposalId.toString(), "reference_count", 1));
        });
    }

    private SourceRow source(
            UUID tenantId,
            UUID leaseId,
            UUID documentId,
            UUID documentVersionId,
            UUID expectedPatient,
            UUID expectedEncounter) {
        return jdbc.sql("""
                select version.sections::text as sections, version.content_hash,
                  lease.authorization_watermark, lease.model_residency_policy, lease.expires_at,
                  document.patient_id, document.encounter_id
                from context_lease lease
                join clinical_document document
                  on document.tenant_id = lease.tenant_id and document.document_id = :document
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                  and version.document_version_id = :version
                where lease.tenant_id = :tenant and lease.lease_id = :lease
                  and lease.patient_id = document.patient_id and lease.encounter_id = document.encounter_id
                  and lease.revoked_at is null and lease.expires_at > now()
                  and (cast(:patient as uuid) is null or document.patient_id = cast(:patient as uuid))
                  and (cast(:encounter as uuid) is null or document.encounter_id = cast(:encounter as uuid))
                """)
                .param("document", documentId).param("version", documentVersionId)
                .param("tenant", tenantId).param("lease", leaseId)
                .param("patient", expectedPatient).param("encounter", expectedEncounter)
                .query((rs, row) -> new SourceRow(
                        rs.getString("sections"), rs.getString("content_hash"),
                        rs.getString("authorization_watermark"), rs.getString("model_residency_policy"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                .optional().orElseThrow(() -> new AgentRunException(
                        "LEASE_EXPIRED", 403, "The AI context lease is invalid or expired"));
    }

    private RunRow run(UUID tenantId, UUID runId) {
        return queryRun(tenantId, runId, false);
    }

    private RunRow runForUpdate(UUID tenantId, UUID runId) {
        return queryRun(tenantId, runId, true);
    }

    private RunRow queryRun(UUID tenantId, UUID runId, boolean lock) {
        String sql = """
                select run_id, context_lease_id, document_id, document_version_id, state, sequence,
                  data_watermark, provider_code, model_code, max_output_tokens, deadline_at, created_at, updated_at
                from ai_run where tenant_id = :tenant and run_id = :run
                """ + (lock ? " for update" : "");
        return jdbc.sql(sql).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RunRow(
                        rs.getObject("run_id", UUID.class), rs.getObject("context_lease_id", UUID.class),
                        rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                        rs.getString("state"), rs.getLong("sequence"), rs.getString("data_watermark"),
                        rs.getString("provider_code"), rs.getString("model_code"), rs.getInt("max_output_tokens"),
                        rs.getObject("deadline_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
                .optional().orElseThrow(AgentRunService::contextDenied);
    }

    private void transition(UUID tenantId, UUID runId, String state, String eventType, Map<String, Object> payload) {
        long sequence = jdbc.sql("""
                update ai_run set state = :state, sequence = sequence + 1,
                  updated_at = now(), row_version = row_version + 1
                where tenant_id = :tenant and run_id = :run
                returning sequence
                """)
                .param("state", state).param("tenant", tenantId).param("run", runId)
                .query(Long.class).single();
        jdbc.sql("""
                insert into ai_run_event(tenant_id, run_id, sequence, event_id, event_type, payload)
                values (:tenant, :run, :sequence, :event, :event_type, cast(:payload as jsonb))
                """)
                .param("tenant", tenantId).param("run", runId).param("sequence", sequence)
                .param("event", UUID.randomUUID()).param("event_type", eventType).param("payload", json(payload)).update();
    }

    private ContextReferenceWire reference(RunRow run, SourceRow source) {
        Map<String, Object> sections = parse(source.sectionsJson());
        String field = sections.containsKey("present_illness") ? "present_illness" : sections.keySet().stream().findFirst().orElse("root");
        String excerpt = String.valueOf(sections.getOrDefault(field, ""));
        if (excerpt.length() > 120) excerpt = excerpt.substring(0, 120);
        return new ContextReferenceWire(
                run.documentVersionId() + ":sections." + field,
                ContextReferenceWire.SourceTypeValue.DOCUMENT_VERSION,
                run.documentVersionId(),
                run.documentVersionId().toString(),
                List.of("sections", field),
                null, null, "sections." + field, null,
                source.contentHash(), excerpt, 1.0,
                List.of(ContextReferenceWire.RetrievalMethodItemValue.SQL),
                source.authorizationWatermark(), Instant.now());
    }

    private void appendDecisionEvidence(
            ClinicalIdentity identity, UUID proposalId, UUID runId, String decision) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|AI_PROPOSAL_" + decision + "|"
                + proposalId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'AI_PROPOSAL', :proposal,
                  :trace, :previous_hash, :event_hash)
                """)
                .param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", "AI_PROPOSAL_" + decision).param("proposal", proposalId)
                .param("trace", trace).param("previous_hash", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'AI_RUN', :run, 1, 'AIProposalDecisionRecorded', 1,
                  jsonb_build_object('proposal_id', :proposal, 'decision', :decision))
                """)
                .param("tenant", identity.tenantId()).param("event", UUID.randomUUID()).param("run", runId)
                .param("proposal", proposalId).param("decision", decision).update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored AI JSON is invalid", invalid);
        }
    }

    private Map<String, Object> map(String json) { return parse(json); }

    private List<ContextReferenceWire> references(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<ContextReferenceWire> references = new ArrayList<>();
            for (JsonNode item : root) {
                references.add(objectMapper.treeToValue(item, ContextReferenceWire.class));
            }
            return List.copyOf(references);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored AI references are invalid", invalid);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("AI value cannot be serialized", invalid);
        }
    }

    private static AgentRunException contextDenied() {
        return new AgentRunException("CONTEXT_NOT_PERMITTED", 403, "The requested AI context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record RunContext(UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {}

    private record Policy(boolean enabled, String provider, String model, String residency, String prompt) {}

    private record SourceRow(
            String sectionsJson,
            String contentHash,
            String authorizationWatermark,
            String residency,
            OffsetDateTime leaseExpiresAt,
            UUID patientId,
            UUID encounterId) {}

    private record RunRow(
            UUID runId,
            UUID contextLeaseId,
            UUID documentId,
            UUID documentVersionId,
            String state,
            long sequence,
            String dataWatermark,
            String providerCode,
            String modelCode,
            int maxOutputTokens,
            OffsetDateTime deadlineAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private record ProposalForDecision(
            UUID runId,
            String status,
            String payload,
            String references,
            OffsetDateTime expiresAt,
            long rowVersion,
            String proposalType) {}

    private record EventRow(long sequence, String eventType, String payload) {}
}
