package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.haoemr.approval.ActionApprovalService;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class MedicalAgentHarnessService {

    private static final Set<String> TARGET_TYPES = Set.of("ENCOUNTER", "DOCUMENT", "RESULT", "TASK", "CARE_PLAN");
    private static final Set<String> AUTHORIZATION_LEVELS = Set.of("READ_ONLY", "STANDARD", "EXTENDED");
    private static final Set<String> CONTEXT_SCOPES = Set.of(
            "RECORDS", "ORDERS", "RESULTS", "TASKS", "ATTACHMENTS", "CONFIGURATION");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final MedicalAgentToolGateway toolGateway;
    private final MedicalAgentModelGateway modelGateway;
    private final AgentOrchestrator orchestrator;
    private final ActionApprovalService actionApprovals;

    MedicalAgentHarnessService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Environment environment,
            MedicalAgentToolGateway toolGateway,
            MedicalAgentModelGateway modelGateway,
            AgentOrchestrator orchestrator,
            ActionApprovalService actionApprovals) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.toolGateway = toolGateway;
        this.modelGateway = modelGateway;
        this.orchestrator = orchestrator;
        this.actionApprovals = actionApprovals;
    }

    List<AgentFamilyView> catalog(UUID tenantId) {
        List<AgentReleaseRow> rows = jdbc.sql("""
                select release.agent_code, release.release_version, release.display_name, release.agent_level,
                  release.parent_agent_code, release.stage_code, release.description, release.doctor_facing_summary,
                  release.category, release.display_role, release.current_action, release.contribution_label, release.output_schema,
                  release.autonomy_level, release.max_steps, release.max_tool_calls, release.max_duration_seconds,
                  (select count(*) from medical_agent_run run
                   where run.tenant_id = :tenant and run.root_agent_code = release.agent_code) as usage_count,
                  coalesce(jsonb_agg(example.question_text order by example.example_order)
                    filter (where example.question_text is not null), '[]'::jsonb)::text as question_examples
                from medical_agent_release release
                left join medical_agent_question_example example
                  on example.agent_code = release.agent_code
                 and example.release_version = release.release_version
                where release.status = 'ACTIVE'
                group by release.agent_code, release.release_version, release.display_name, release.agent_level,
                  release.parent_agent_code, release.stage_code, release.description, release.doctor_facing_summary,
                  release.category, release.display_role, release.current_action, release.contribution_label, release.output_schema,
                  release.autonomy_level, release.max_steps, release.max_tool_calls, release.max_duration_seconds
                order by case when release.agent_level = 'MAIN' then 0 else 1 end,
                  release.parent_agent_code, release.stage_code, release.agent_code
                """).param("tenant", tenantId)
                .query((rs, row) -> new AgentReleaseRow(
                        rs.getString("agent_code"), rs.getString("release_version"), rs.getString("display_name"),
                        rs.getString("agent_level"), rs.getString("parent_agent_code"), rs.getString("stage_code"),
                        rs.getString("description"), rs.getString("doctor_facing_summary"), rs.getString("category"),
                        rs.getString("display_role"), rs.getString("current_action"),
                        rs.getString("contribution_label"), rs.getString("output_schema"),
                        rs.getString("autonomy_level"), rs.getInt("max_steps"), rs.getInt("max_tool_calls"),
                        rs.getInt("max_duration_seconds"), rs.getInt("usage_count"),
                        listOfStrings(rs.getString("question_examples")))).list();
        Map<String, List<AgentReleaseView>> children = new LinkedHashMap<>();
        rows.stream().filter(row -> "CHILD".equals(row.level())).forEach(row ->
                children.computeIfAbsent(row.parentCode(), ignored -> new ArrayList<>()).add(view(row)));
        return rows.stream().filter(row -> "MAIN".equals(row.level()))
                .map(row -> new AgentFamilyView(view(row), List.copyOf(children.getOrDefault(row.code(), List.of()))))
                .toList();
    }

    RunView createAndRun(ClinicalIdentity identity, String idempotencyKey, CreateRunCommand requested) {
        CreateRunCommand command = resolveRouting(requested);
        validate(command);
        UUID runId = transactions.execute(status -> {
            LeaseRow lease = lease(identity, command);
            validateTarget(identity.tenantId(), command);
            MainReleaseRow main = main(command.mainAgentCode());
            ensureGovernanceReady(identity.tenantId(), main.agentCode());
            RuntimePolicy policy = runtimePolicy(identity.tenantId());
            ensurePolicyRateLimit(identity, policy);
            ModelSelection model = resolveModel(identity.tenantId(), command.modelDeploymentId());
            ensureModelResidency(lease.residencyPolicy(), model.residencyPolicy());
            ExternalProcessingApproval processingApproval = externalProcessingApproval(
                    identity.tenantId(), model, command.contextScopes(), null);
            List<NodeRow> nodes = nodes(main.compositionCode(), main.compositionVersion(), command.stageCode());
            if (nodes.isEmpty()) {
                throw new AgentRunException("AGENT_STAGE_UNSUPPORTED", 409,
                        "The selected main agent has no approved child for this clinical stage");
            }
            String requestHash = sha256(command.contextLeaseId() + "|" + command.mainAgentCode() + "|"
                    + command.stageCode() + "|" + command.targetType() + "|" + command.targetId() + "|"
                    + command.objective() + "|" + model.deploymentId() + "|" + command.authorizationLevel()
                    + "|" + command.contextScopes());
            beginCommand(identity, idempotencyKey, requestHash);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into medical_agent_run(
                      tenant_id, run_id, context_lease_id, root_agent_code, root_agent_version,
                      composition_code, composition_version, requested_stage, patient_id, encounter_id,
                      target_type, target_id, objective, model_deployment_id,
                      external_processing_approval_id, assistant_policy_config_id,
                      assistant_policy_row_version, assistant_policy_hash, assistant_policy_environment,
                      authorization_level,
                      context_scopes, state, created_by)
                    values (:tenant, :run, :lease, :root, :root_version, :composition, :composition_version,
                      :stage, :patient, :encounter, :target_type, :target_id, :objective, :model, :approval,
                      :policy_id, :policy_version, :policy_hash, :policy_environment,
                      :authorization, cast(:scopes as jsonb), 'QUEUED', :actor)
                    """).param("tenant", identity.tenantId()).param("run", id)
                    .param("lease", command.contextLeaseId()).param("root", main.agentCode())
                    .param("root_version", main.agentVersion()).param("composition", main.compositionCode())
                    .param("composition_version", main.compositionVersion()).param("stage", command.stageCode())
                    .param("patient", command.patientId()).param("encounter", command.encounterId())
                    .param("target_type", command.targetType()).param("target_id", command.targetId())
                    .param("objective", command.objective().trim()).param("model", model.deploymentId())
                    .param("approval", processingApproval.approvalId())
                    .param("policy_id", policy.configId()).param("policy_version", policy.rowVersion())
                    .param("policy_hash", policy.payloadHash()).param("policy_environment", policy.policyEnvironment())
                    .param("authorization", command.authorizationLevel()).param("scopes", json(command.contextScopes()))
                    .param("actor", identity.userId()).update();
            Map<String, Object> creationEvidence = new LinkedHashMap<>();
            creationEvidence.put("root_agent_code", main.agentCode());
            creationEvidence.put("stage_code", command.stageCode());
            creationEvidence.put("candidate_only", true);
            creationEvidence.put("model_deployment_id", model.deploymentId());
            creationEvidence.put("model_display_name", model.displayName());
            creationEvidence.put("authorization_level", command.authorizationLevel());
            creationEvidence.put("context_scopes", command.contextScopes());
            creationEvidence.put("assistant_policy_config_id", policy.configId());
            creationEvidence.put("assistant_policy_row_version", policy.rowVersion());
            creationEvidence.put("assistant_policy_hash", policy.payloadHash());
            creationEvidence.put("assistant_policy_environment", policy.policyEnvironment());
            if (processingApproval.approvalId() != null) {
                creationEvidence.put("external_processing_approval_id", processingApproval.approvalId());
            }
            appendEvent(identity.tenantId(), id, null, "RunCreated", creationEvidence);
            completeCommand(identity, idempotencyKey, id);
            return id;
        });
        return run(identity.tenantId(), runId);
    }

    void executeClaimed(UUID tenantId, UUID runId, UUID workerId, int leaseSeconds) {
        long runStarted = System.nanoTime();
        ExecutionSnapshot snapshot = executionSnapshot(tenantId, runId, workerId);
        renewWorkerLease(tenantId, runId, workerId, leaseSeconds);
        ClinicalIdentity identity = new ClinicalIdentity(tenantId, snapshot.createdBy(), List.of());
        ActiveBudget budget = ensureGovernanceReady(tenantId, snapshot.rootAgentCode());
        enforceRunBudget(budget, runStarted, 0);
        ModelSelection model = modelById(tenantId, snapshot.modelDeploymentId());
        ensureModelResidency(snapshot.modelResidencyPolicy(), model.residencyPolicy());
        CreateRunCommand command = snapshot.command();
        ensureRuntimePolicyStillActive(tenantId, snapshot);
        externalProcessingApproval(tenantId, model, command.contextScopes(),
                snapshot.externalProcessingApprovalId());
        List<NodeRow> nodes = nodes(snapshot.compositionCode(), snapshot.compositionVersion(), snapshot.stageCode());
        appendEvent(tenantId, runId, null, "MainAgentStarted", Map.of(
                "root_agent_code", snapshot.rootAgentCode(), "composition_code", snapshot.compositionCode(),
                "attempt", snapshot.attempt()));
        List<Map<String, Object>> contributions = new ArrayList<>();
        ContextFacts facts = ContextFacts.empty();
        boolean partial = false;
        boolean criticalFailure = false;
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        long modelDurationMs = 0;
        int modelRequestCount = 0;
        int toolCallCount = 0;
        String executionMode = "NOT_STARTED";
        for (NodeRow node : nodes) {
            renewWorkerLease(tenantId, runId, workerId, leaseSeconds);
            if (cancellationRequested(tenantId, runId, workerId)) {
                finalizeCancellation(identity, runId, workerId, "CHECKPOINT_BEFORE_CHILD");
                return;
            }
            UUID childRunId = startChild(tenantId, runId, node);
            appendEvent(tenantId, runId, childRunId, "ChildAgentStarted", Map.of(
                    "child_agent_code", node.agentCode(), "display_name", node.displayName(),
                    "current_action", node.currentAction(), "attempt", snapshot.attempt()));
            Map<String, Object> contribution;
            List<Map<String, Object>> sourceRefs = List.of();
            String childState;
            String childFailureCode = null;
            try {
                enforceRunBudget(budget, runStarted, totalTokens);
                appendEvent(tenantId, runId, childRunId, "ToolExecutionStarted", Map.of(
                        "context_scopes", command.contextScopes(), "authorization_level",
                        command.authorizationLevel()));
                List<MedicalAgentToolGateway.ToolResult> toolResults = toolGateway.execute(
                        tenantId, runId, childRunId, command.contextLeaseId(), command.patientId(),
                        command.encounterId(), snapshot.watermark(), snapshot.rootAgentCode(),
                        Set.copyOf(command.contextScopes()), node.displayName() + " " + command.objective());
                enforceRunBudget(budget, runStarted, totalTokens);
                toolCallCount += toolResults.size();
                for (MedicalAgentToolGateway.ToolResult tool : toolResults) {
                    appendEvent(tenantId, runId, childRunId, "ToolCompleted", Map.of(
                            "invocation_id", tool.invocationId(), "tool_code", tool.toolCode(),
                            "tool_version", tool.toolVersion(), "item_count", tool.items().size(),
                            "duration_ms", tool.durationMs()));
                }
                if (cancellationRequested(tenantId, runId, workerId)) {
                    cancelChild(tenantId, childRunId);
                    finalizeCancellation(identity, runId, workerId, "CHECKPOINT_AFTER_TOOLS");
                    return;
                }
                ContextFacts childFacts = contextFacts(toolResults);
                if (facts.totalCount() == 0) facts = childFacts;
                sourceRefs = toolResults.stream().flatMap(tool -> tool.sourceReferences().stream()).toList();
                appendEvent(tenantId, runId, childRunId, "ModelGenerationStarted", Map.of(
                        "provider_code", model.providerCode(), "model_code", model.modelCode(),
                        "model_display_name", model.displayName(), "model_deployment_id", model.deploymentId(),
                        "tool_call_count", toolResults.size()));
                modelRequestCount += 1;
                int maxOutputTokens = maxOutputTokens(budget, runStarted, totalTokens);
                MedicalAgentModelGateway.ModelResult generated = modelGateway.generate(
                        new MedicalAgentModelGateway.ModelRequest(model.providerCode(), model.modelCode(),
                                model.endpointUrl(), model.apiKeyReference(), node.agentCode(), node.displayName(),
                                node.currentAction(), node.outputSchema(), command.objective().trim(),
                                toolResults.stream().map(tool -> new MedicalAgentModelGateway.ToolEvidence(
                                        tool.toolCode(), tool.displayName(), tool.items())).toList(), maxOutputTokens));
                renewWorkerLease(tenantId, runId, workerId, leaseSeconds);
                if (cancellationRequested(tenantId, runId, workerId)) {
                    cancelChild(tenantId, childRunId);
                    finalizeCancellation(identity, runId, workerId, "CHECKPOINT_AFTER_MODEL");
                    return;
                }
                promptTokens += generated.promptTokens();
                completionTokens += generated.completionTokens();
                totalTokens += generated.totalTokens();
                modelDurationMs += generated.durationMs();
                enforceRunBudget(budget, runStarted, totalTokens);
                executionMode = generated.executionMode();
                contribution = contribution(node, command, generated, toolResults);
                childState = sourceRefs.isEmpty() ? "PARTIAL" : "COMPLETED";
                partial |= "PARTIAL".equals(childState);
                appendEvent(tenantId, runId, childRunId, "ModelGenerationCompleted", Map.of(
                        "request_id", generated.requestId() == null ? "UNAVAILABLE" : generated.requestId(),
                        "execution_mode", generated.executionMode(), "prompt_tokens", generated.promptTokens(),
                        "completion_tokens", generated.completionTokens(), "total_tokens", generated.totalTokens(),
                        "duration_ms", generated.durationMs()));
            } catch (RuntimeException failure) {
                if (isBudgetFailure(failure)) throw failure;
                String errorCode = errorCode(failure);
                if (failure instanceof ModelProviderUnavailableException modelFailure) {
                    promptTokens += modelFailure.promptTokens();
                    completionTokens += modelFailure.completionTokens();
                    totalTokens += modelFailure.totalTokens();
                    modelDurationMs += modelFailure.durationMs();
                    executionMode = modelFailure.executionMode();
                    appendEvent(tenantId, runId, childRunId, "ModelGenerationFailed", Map.of(
                            "request_id", modelFailure.requestId() == null
                                    ? "UNAVAILABLE" : modelFailure.requestId(),
                            "execution_mode", modelFailure.executionMode(),
                            "prompt_tokens", modelFailure.promptTokens(),
                            "completion_tokens", modelFailure.completionTokens(),
                            "total_tokens", modelFailure.totalTokens(),
                            "duration_ms", modelFailure.durationMs(),
                            "error_code", modelFailure.code()));
                }
                childFailureCode = errorCode;
                childState = "FAILED";
                partial = true;
                criticalFailure |= node.critical();
                contribution = Map.of(
                        "agent_code", node.agentCode(), "display_name", node.displayName(),
                        "summary", "子医助执行失败，未生成临床候选。",
                        "facts", List.of(), "gaps", List.of("未完成范围：" + node.contributionLabel()),
                        "warnings", List.of("请转人工流程或在修复模型/工具后重试。"),
                        "error_code", errorCode, "candidate_only", true);
                appendEvent(tenantId, runId, childRunId, "ChildAgentFailed", Map.of(
                        "child_agent_code", node.agentCode(), "error_code", errorCode,
                        "critical", node.critical()));
            }
            completeChild(tenantId, childRunId, childState, contribution, sourceRefs);
            appendEvent(tenantId, runId, childRunId, "ChildContributionReady", Map.of(
                    "child_agent_code", node.agentCode(), "state", childState,
                    "source_reference_count", sourceRefs.size(), "contribution_label", node.contributionLabel()));
            appendEvent(tenantId, runId, childRunId, "ChildHandoffReceived", Map.of(
                    "from", node.agentCode(), "to", snapshot.rootAgentCode(), "state", childState));
            contributions.add(contribution);
            if (node.critical() && childFailureCode != null) {
                throw new AgentRunException(childFailureCode, 502,
                        "A critical medical assistant step failed and will be retried when permitted");
            }
        }
        if (contributions.stream().noneMatch(value -> value.containsKey("model_request_id"))) {
            criticalFailure = true;
        }
        String finalState = criticalFailure ? "FAILED" : partial ? "PARTIAL" : "WAITING_FOR_REVIEW";
        Map<String, Object> output = output(snapshot, command, model, contributions, facts, criticalFailure, partial,
                executionMode, promptTokens, completionTokens, totalTokens, modelRequestCount,
                modelDurationMs, toolCallCount);
        long actualDurationMs = Math.max(0, (System.nanoTime() - runStarted) / 1_000_000);
        enforceRunBudget(budget, runStarted, totalTokens);
        int updated = jdbc.sql("""
                update medical_agent_run set state = :state, output_payload = cast(:output as jsonb),
                  model_prompt_tokens = :prompt_tokens, model_completion_tokens = :completion_tokens,
                  model_total_tokens = :total_tokens, actual_duration_ms = :duration_ms,
                  model_request_count = :model_requests, tool_call_count = :tool_calls,
                  sequence = sequence + 1, completed_at = now(), failure_code = :failure,
                  worker_lease_owner = null, worker_lease_until = null, last_heartbeat_at = now(),
                  row_version = row_version + 1
                where tenant_id = :tenant and run_id = :run and state = 'RUNNING'
                  and worker_lease_owner = :worker and cancel_requested_at is null
                """).param("state", finalState).param("output", json(output))
                .param("prompt_tokens", promptTokens).param("completion_tokens", completionTokens)
                .param("total_tokens", totalTokens).param("duration_ms", actualDurationMs)
                .param("model_requests", modelRequestCount).param("tool_calls", toolCallCount)
                .param("failure", criticalFailure ? "MEDICAL_AGENT_CRITICAL_CHILD_FAILED" : null)
                .param("tenant", tenantId).param("run", runId).param("worker", workerId).update();
        if (updated != 1) {
            if (cancellationRequested(tenantId, runId, workerId)) {
                finalizeCancellation(identity, runId, workerId, "CHECKPOINT_BEFORE_FINALIZE");
            }
            return;
        }
        insertEventAtCurrentSequence(tenantId, runId,
                criticalFailure ? "RunFailed" : "RunReadyForReview", Map.of(
                "state", finalState, "child_count", contributions.size(), "candidate_only", true,
                "attempt", snapshot.attempt()));
        int durationSeconds = (int) Math.min(budget.maxDurationSeconds(),
                Math.max(0L, (actualDurationMs + 999L) / 1000L));
        jdbc.sql("""
                insert into agent_run_budget_consumption(
                  tenant_id, consumption_id, budget_id, run_id, tokens_consumed,
                  duration_seconds, recorded_by, recorded_at, attempt)
                values (:tenant, :consumption, :budget, :run, :tokens, :duration, :actor, now(), :attempt)
                """).param("tenant", tenantId).param("consumption", UUID.randomUUID())
                .param("budget", budget.budgetId()).param("run", runId).param("tokens", totalTokens)
                .param("duration", durationSeconds).param("actor", identity.userId())
                .param("attempt", snapshot.attempt()).update();
        appendEvent(tenantId, runId, null, "BudgetConsumptionRecorded", Map.of(
                "budget_code", budget.budgetCode(), "tokens_consumed", totalTokens,
                "duration_seconds", durationSeconds, "attempt", snapshot.attempt()));
        String evidencedState = finalState;
        if ("WAITING_FOR_REVIEW".equals(finalState) && command.patientId() != null) {
            try {
                ActionApprovalService.AgentProposalReceipt proposal = actionApprovals.proposeFromMedicalAgent(tenantId, runId);
                if (proposal.created()) {
                    appendEvent(tenantId, runId, null, "ActionApprovalQueued", Map.of(
                            "action_approval_id", proposal.approvalId(), "action_type", "OTHER",
                            "execution_boundary", "INDEPENDENT_CLINICAL_REVIEW_REQUIRED"));
                }
            } catch (RuntimeException failure) {
                if (markActionApprovalQueueFailed(tenantId, runId, failure)) {
                    evidencedState = "PARTIAL";
                }
            }
        }
        appendEvidence(identity, runId, snapshot.rootAgentCode(), evidencedState);
    }

    /** A run may be review-ready only when its required independent-review candidate was queued. */
    private boolean markActionApprovalQueueFailed(UUID tenantId, UUID runId, RuntimeException failure) {
        Long sequence = jdbc.sql("""
                update medical_agent_run set state = 'PARTIAL', failure_code = :failure,
                  completed_at = now(), sequence = sequence + 1, row_version = row_version + 1
                where tenant_id = :tenant and run_id = :run and state = 'WAITING_FOR_REVIEW'
                returning sequence
                """).param("failure", "MEDICAL_AGENT_ACTION_PROPOSAL_" + errorCode(failure))
                .param("tenant", tenantId).param("run", runId).query(Long.class).optional().orElse(null);
        if (sequence == null) return false;
        insertEventAtCurrentSequence(tenantId, runId, "ActionApprovalQueueFailed", Map.of(
                "state", "PARTIAL", "failure_code", "MEDICAL_AGENT_ACTION_PROPOSAL_" + errorCode(failure),
                "execution_boundary", "NO_AUTOMATIC_CLINICAL_EFFECT"));
        return true;
    }

    private void renewWorkerLease(UUID tenantId, UUID runId, UUID workerId, int leaseSeconds) {
        int updated = jdbc.sql("""
                update medical_agent_run set
                  worker_lease_until = now() + (:lease_seconds * interval '1 second'),
                  last_heartbeat_at = now()
                where tenant_id = :tenant and run_id = :run and state = 'RUNNING'
                  and worker_lease_owner = :worker and worker_lease_until > now()
                """).param("lease_seconds", leaseSeconds).param("tenant", tenantId)
                .param("run", runId).param("worker", workerId).update();
        if (updated != 1) {
            throw new AgentRunException("MEDICAL_AGENT_WORKER_FENCE_LOST", 409,
                    "The worker no longer owns this medical assistant run");
        }
    }

    RunView run(UUID tenantId, UUID runId) {
        RootRunRow root = jdbc.sql("""
                select run_id, context_lease_id, root_agent_code, root_agent_version, composition_code,
                  composition_version, requested_stage, patient_id, encounter_id, target_type, target_id,
                  objective, state, sequence, output_payload::text, created_at, completed_at, row_version
                  , attempt, max_attempts, cancel_requested_at, failure_code
                from medical_agent_run where tenant_id = :tenant and run_id = :run
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RootRunRow(
                        rs.getObject("run_id", UUID.class), rs.getObject("context_lease_id", UUID.class),
                        rs.getString("root_agent_code"), rs.getString("root_agent_version"),
                        rs.getString("composition_code"), rs.getString("composition_version"),
                        rs.getString("requested_stage"), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("objective"), rs.getString("state"),
                        rs.getLong("sequence"), rs.getString("output_payload"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class), rs.getLong("row_version"),
                        rs.getInt("attempt"), rs.getInt("max_attempts"),
                        rs.getObject("cancel_requested_at", OffsetDateTime.class), rs.getString("failure_code")))
                .optional().orElseThrow(MedicalAgentHarnessService::contextDenied);
        List<ChildRunView> children = jdbc.sql("""
                select child.child_run_id, child.child_agent_code, release.display_name, release.display_role,
                  release.current_action, release.contribution_label, child.state, child.critical,
                  child.contribution::text, child.source_references::text, child.started_at, child.completed_at
                from medical_agent_child_run child
                join medical_agent_release release on release.agent_code = child.child_agent_code
                  and release.release_version = child.child_agent_version
                where child.tenant_id = :tenant and child.root_run_id = :run
                order by child.started_at, child.child_run_id
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new ChildRunView(
                        rs.getObject("child_run_id", UUID.class), rs.getString("child_agent_code"),
                        rs.getString("display_name"), rs.getString("display_role"), rs.getString("current_action"),
                        rs.getString("contribution_label"), rs.getString("state"), rs.getBoolean("critical"),
                        map(rs.getString("contribution")), listOfMaps(rs.getString("source_references")),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class))).list();
        List<RunEventView> events = jdbc.sql("""
                select sequence, event_type, child_run_id, payload::text, occurred_at
                from medical_agent_run_event where tenant_id = :tenant and run_id = :run order by sequence
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RunEventView(rs.getLong("sequence"), rs.getString("event_type"),
                        rs.getObject("child_run_id", UUID.class), map(rs.getString("payload")),
                        rs.getObject("occurred_at", OffsetDateTime.class))).list();
        return new RunView(root.runId(), root.contextLeaseId(), root.rootAgentCode(), root.rootAgentVersion(),
                root.compositionCode(), root.compositionVersion(), root.stageCode(), root.patientId(),
                root.encounterId(), root.targetType(), root.targetId(), root.objective(), root.state(), root.sequence(),
                map(root.output()), root.createdAt(), root.completedAt(), root.rowVersion(), root.attempt(),
                root.maxAttempts(), root.cancelRequestedAt(), root.failureCode(), children, events);
    }

    List<RunView> listRuns(UUID tenantId, UUID encounterId) {
        List<UUID> ids = jdbc.sql("""
                select run_id from medical_agent_run
                where tenant_id = :tenant and encounter_id = :encounter
                order by created_at desc, run_id desc limit 100
                """).param("tenant", tenantId).param("encounter", encounterId).query(UUID.class).list();
        return ids.stream().map(id -> run(tenantId, id)).toList();
    }

    List<OperationsRunView> operationsRuns(UUID tenantId, UUID facilityId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return jdbc.sql("""
                select run.run_id, run.root_agent_code, release.display_name as root_agent_name,
                  run.requested_stage, run.state, model.display_name as model_display_name,
                  model.provider_code, run.authorization_level, run.model_total_tokens,
                  run.actual_duration_ms, run.model_request_count, run.tool_call_count,
                  count(invocation.invocation_id) filter (where invocation.outcome = 'FAILED') as tool_failure_count,
                  run.attempt, run.max_attempts, run.failure_code, run.created_at, run.completed_at,
                  run.external_processing_approval_id is not null as external_processing_approved,
                  run.assistant_policy_environment
                from medical_agent_run run
                join context_lease lease
                  on lease.tenant_id = run.tenant_id and lease.lease_id = run.context_lease_id
                join medical_agent_release release
                  on release.agent_code = run.root_agent_code and release.release_version = run.root_agent_version
                left join model_deployment model
                  on model.tenant_id = run.tenant_id
                 and model.model_deployment_id = run.model_deployment_id
                left join medical_agent_tool_invocation invocation
                  on invocation.tenant_id = run.tenant_id and invocation.root_run_id = run.run_id
                where run.tenant_id = :tenant and lease.facility_id = :facility
                group by run.run_id, run.root_agent_code, release.display_name, run.requested_stage,
                  run.state, model.display_name, model.provider_code, run.authorization_level,
                  run.model_total_tokens, run.actual_duration_ms, run.model_request_count,
                  run.tool_call_count, run.attempt, run.max_attempts, run.failure_code,
                  run.created_at, run.completed_at, run.external_processing_approval_id,
                  run.assistant_policy_environment
                order by run.created_at desc, run.run_id desc
                limit :limit
                """).param("tenant", tenantId).param("facility", facilityId).param("limit", limit)
                .query((rs, row) -> new OperationsRunView(
                        rs.getObject("run_id", UUID.class), rs.getString("root_agent_code"),
                        rs.getString("root_agent_name"), rs.getString("requested_stage"),
                        rs.getString("state"), rs.getString("model_display_name"),
                        rs.getString("provider_code"), rs.getString("authorization_level"),
                        rs.getLong("model_total_tokens"), rs.getLong("actual_duration_ms"),
                        rs.getInt("model_request_count"), rs.getInt("tool_call_count"),
                        rs.getInt("tool_failure_count"), rs.getInt("attempt"), rs.getInt("max_attempts"),
                        rs.getString("failure_code"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class),
                        rs.getBoolean("external_processing_approved"),
                        rs.getString("assistant_policy_environment")))
                .list();
    }

    List<OperationsToolInvocationView> operationsToolInvocations(
            UUID tenantId, UUID facilityId, UUID runId) {
        if (jdbc.sql("""
                select count(*) from medical_agent_run run
                join context_lease lease
                  on lease.tenant_id = run.tenant_id and lease.lease_id = run.context_lease_id
                where run.tenant_id = :tenant and lease.facility_id = :facility and run.run_id = :run
                """).param("tenant", tenantId).param("facility", facilityId).param("run", runId)
                .query(Integer.class).single() != 1) {
            throw contextDenied();
        }
        return jdbc.sql("""
                select invocation_id, child_run_id, tool_code, tool_version, item_count, outcome,
                  duration_ms, error_code, invoked_at, completed_at
                from medical_agent_tool_invocation
                where tenant_id = :tenant and root_run_id = :run
                order by invoked_at, invocation_id
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new OperationsToolInvocationView(
                        rs.getObject("invocation_id", UUID.class), rs.getObject("child_run_id", UUID.class),
                        rs.getString("tool_code"), rs.getString("tool_version"), rs.getInt("item_count"),
                        rs.getString("outcome"), rs.getLong("duration_ms"), rs.getString("error_code"),
                        rs.getObject("invoked_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class)))
                .list();
    }

    Optional<WorkerClaim> claimNext(UUID workerId, int leaseSeconds) {
        return transactions.execute(status -> {
            Optional<WorkerClaim> claim = jdbc.sql("""
                    with candidate as (
                      select tenant_id, run_id from medical_agent_run
                      where state = 'QUEUED' and available_at <= now() and cancel_requested_at is null
                        and attempt < max_attempts
                      order by available_at, created_at, run_id
                      for update skip locked limit 1
                    )
                    update medical_agent_run run set state = 'RUNNING', attempt = run.attempt + 1,
                      worker_lease_owner = :worker,
                      worker_lease_until = now() + (:lease_seconds * interval '1 second'),
                      last_heartbeat_at = now(), failure_code = null,
                      sequence = run.sequence + 1, row_version = run.row_version + 1
                    from candidate
                    where run.tenant_id = candidate.tenant_id and run.run_id = candidate.run_id
                    returning run.tenant_id, run.run_id, run.attempt, run.sequence
                    """).param("worker", workerId).param("lease_seconds", leaseSeconds)
                    .query((rs, row) -> new WorkerClaim(
                            rs.getObject("tenant_id", UUID.class), rs.getObject("run_id", UUID.class),
                            rs.getInt("attempt"), rs.getLong("sequence"))).optional();
            claim.ifPresent(item -> insertEvent(item.tenantId(), item.runId(), item.sequence(), null,
                    "RunClaimed", Map.of("worker_id", workerId, "attempt", item.attempt(),
                            "lease_seconds", leaseSeconds)));
            return claim;
        });
    }

    int reclaimExpiredWorkerLeases() {
        return transactions.execute(status -> {
            List<ExpiredRun> expired = jdbc.sql("""
                    update medical_agent_run set
                      state = case when cancel_requested_at is null then 'QUEUED' else 'CANCELLED' end,
                      available_at = case when cancel_requested_at is null then now() else available_at end,
                      completed_at = case when cancel_requested_at is null then null else now() end,
                      failure_code = case when cancel_requested_at is null then 'WORKER_LEASE_EXPIRED' else null end,
                      worker_lease_owner = null, worker_lease_until = null,
                      sequence = sequence + 1, row_version = row_version + 1
                    where state = 'RUNNING' and worker_lease_until < now()
                    returning tenant_id, run_id, state, sequence, attempt
                    """).query((rs, row) -> new ExpiredRun(
                            rs.getObject("tenant_id", UUID.class), rs.getObject("run_id", UUID.class),
                            rs.getString("state"), rs.getLong("sequence"), rs.getInt("attempt"))).list();
            for (ExpiredRun item : expired) {
                insertEvent(item.tenantId(), item.runId(), item.sequence(), null,
                        "CANCELLED".equals(item.state()) ? "RunCancelled" : "RunLeaseExpired", Map.of(
                                "state", item.state(), "attempt", item.attempt(),
                                "reason", "WORKER_LEASE_EXPIRED"));
            }
            return expired.size();
        });
    }

    void recordWorkerFailure(WorkerClaim claim, UUID workerId, RuntimeException failure) {
        transactions.executeWithoutResult(status -> {
            WorkerFailureHead current = jdbc.sql("""
                    select attempt, max_attempts, cancel_requested_at is not null as cancellation
                    from medical_agent_run where tenant_id = :tenant and run_id = :run
                      and state = 'RUNNING' and worker_lease_owner = :worker for update
                    """).param("tenant", claim.tenantId()).param("run", claim.runId()).param("worker", workerId)
                    .query((rs, row) -> new WorkerFailureHead(
                            rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getBoolean("cancellation")))
                    .optional().orElse(null);
            if (current == null) return;
            String code = errorCode(failure);
            boolean cancelled = current.cancellation();
            boolean budgetBlocked = isBudgetFailure(failure);
            boolean retry = !cancelled && !budgetBlocked && current.attempt() < current.maxAttempts();
            String state = cancelled ? "CANCELLED" : budgetBlocked ? "BLOCKED" : retry ? "QUEUED" : "FAILED";
            long sequence = jdbc.sql("""
                    update medical_agent_run set state = :state,
                      available_at = case when :state = 'QUEUED'
                        then now() + ((attempt * attempt) * interval '1 second') else available_at end,
                      completed_at = case when :state in ('FAILED','CANCELLED') then now() else null end,
                      failure_code = :failure, worker_lease_owner = null, worker_lease_until = null,
                      sequence = sequence + 1, row_version = row_version + 1
                    where tenant_id = :tenant and run_id = :run and worker_lease_owner = :worker
                    returning sequence
                    """).param("state", state).param("failure", cancelled ? null : code)
                    .param("tenant", claim.tenantId()).param("run", claim.runId()).param("worker", workerId)
                    .query(Long.class).single();
            insertEvent(claim.tenantId(), claim.runId(), sequence, null,
                    cancelled ? "RunCancelled" : budgetBlocked ? "RunBlocked" : retry ? "RunRetryScheduled" : "RunFailed", Map.of(
                            "state", state, "attempt", current.attempt(),
                            "error_code", cancelled ? "CANCELLED_BY_USER" : code));
        });
    }

    RunView requestCancellation(
            ClinicalIdentity identity, UUID runId, long expectedRowVersion, String reason) {
        String normalizedReason = requireReason(reason);
        transactions.executeWithoutResult(status -> {
            RunControlHead current = runControlHead(identity.tenantId(), runId);
            if (current.rowVersion() != expectedRowVersion) {
                throw new AgentRunException("MEDICAL_AGENT_RUN_VERSION_CONFLICT", 409,
                        "The medical assistant run changed; reload before cancelling");
            }
            if (!Set.of("QUEUED", "RUNNING").contains(current.state())) {
                throw new AgentRunException("MEDICAL_AGENT_RUN_STATE_INVALID", 409,
                        "Only a queued or running medical assistant task can be cancelled");
            }
            String targetState = "QUEUED".equals(current.state()) ? "CANCELLED" : "RUNNING";
            long sequence = jdbc.sql("""
                    update medical_agent_run set state = :state, cancel_requested_at = now(),
                      cancel_requested_by = :actor,
                      completed_at = case when :state = 'CANCELLED' then now() else completed_at end,
                      worker_lease_owner = case when :state = 'CANCELLED' then null else worker_lease_owner end,
                      worker_lease_until = case when :state = 'CANCELLED' then null else worker_lease_until end,
                      sequence = sequence + 1, row_version = row_version + 1
                    where tenant_id = :tenant and run_id = :run and row_version = :expected
                    returning sequence
                    """).param("state", targetState).param("actor", identity.userId())
                    .param("tenant", identity.tenantId()).param("run", runId).param("expected", expectedRowVersion)
                    .query(Long.class).single();
            insertEvent(identity.tenantId(), runId, sequence, null,
                    "CANCELLED".equals(targetState) ? "RunCancelled" : "RunCancellationRequested",
                    Map.of("reason", normalizedReason, "requested_by", identity.userId(), "state", targetState));
        });
        return run(identity.tenantId(), runId);
    }

    RunView retry(
            ClinicalIdentity identity, UUID runId, RetryRunCommand command) {
        transactions.executeWithoutResult(status -> {
            RunRetryHead current = jdbc.sql("""
                    select state, row_version, patient_id, encounter_id
                    from medical_agent_run where tenant_id = :tenant and run_id = :run for update
                    """).param("tenant", identity.tenantId()).param("run", runId)
                    .query((rs, row) -> new RunRetryHead(rs.getString("state"), rs.getLong("row_version"),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                    .optional().orElseThrow(MedicalAgentHarnessService::contextDenied);
            if (current.rowVersion() != command.expectedRowVersion()) {
                throw new AgentRunException("MEDICAL_AGENT_RUN_VERSION_CONFLICT", 409,
                        "The medical assistant run changed; reload before retrying");
            }
            if (!Set.of("FAILED", "BLOCKED", "PARTIAL", "CANCELLED").contains(current.state())) {
                throw new AgentRunException("MEDICAL_AGENT_RUN_STATE_INVALID", 409,
                        "Only a failed, blocked, partial or cancelled medical assistant task can be retried");
            }
            ensureRuntimeLease(identity, command, current.patientId(), current.encounterId());
            long sequence = jdbc.sql("""
                    update medical_agent_run set state = 'QUEUED', context_lease_id = :lease,
                      attempt = 0, available_at = now(), completed_at = null, output_payload = '{}'::jsonb,
                      worker_lease_owner = null, worker_lease_until = null, last_heartbeat_at = null,
                      cancel_requested_at = null, cancel_requested_by = null, failure_code = null,
                      model_prompt_tokens = 0, model_completion_tokens = 0, model_total_tokens = 0,
                      actual_duration_ms = 0, model_request_count = 0, tool_call_count = 0,
                      sequence = sequence + 1, row_version = row_version + 1
                    where tenant_id = :tenant and run_id = :run and row_version = :expected
                    returning sequence
                    """).param("lease", command.contextLeaseId()).param("tenant", identity.tenantId())
                    .param("run", runId).param("expected", command.expectedRowVersion())
                    .query(Long.class).single();
            insertEvent(identity.tenantId(), runId, sequence, null, "RunRetryRequested", Map.of(
                    "previous_state", current.state(), "requested_by", identity.userId()));
        });
        return run(identity.tenantId(), runId);
    }

    private ExecutionSnapshot executionSnapshot(UUID tenantId, UUID runId, UUID workerId) {
        return jdbc.sql("""
                select run.context_lease_id, lease.organization_id, lease.facility_id,
                  lease.authorization_watermark, lease.model_residency_policy,
                  run.created_by, run.root_agent_code,
                  run.root_agent_version, run.composition_code, run.composition_version,
                  run.requested_stage, run.patient_id, run.encounter_id, run.target_type,
                  run.target_id, run.objective, run.model_deployment_id, run.authorization_level,
                  run.context_scopes::text, run.external_processing_approval_id,
                  run.assistant_policy_config_id, run.assistant_policy_row_version,
                  run.assistant_policy_hash, run.assistant_policy_environment, run.attempt
                from medical_agent_run run
                join context_lease lease on lease.tenant_id = run.tenant_id
                  and lease.lease_id = run.context_lease_id
                where run.tenant_id = :tenant and run.run_id = :run and run.state = 'RUNNING'
                  and run.worker_lease_owner = :worker and run.worker_lease_until > now()
                """).param("tenant", tenantId).param("run", runId).param("worker", workerId)
                .query((rs, row) -> {
                    UUID leaseId = rs.getObject("context_lease_id", UUID.class);
                    UUID organizationId = rs.getObject("organization_id", UUID.class);
                    UUID facilityId = rs.getObject("facility_id", UUID.class);
                    UUID patientId = rs.getObject("patient_id", UUID.class);
                    UUID encounterId = rs.getObject("encounter_id", UUID.class);
                    CreateRunCommand command = new CreateRunCommand(
                            organizationId, facilityId, patientId, encounterId, leaseId,
                            rs.getString("root_agent_code"), rs.getString("requested_stage"),
                            rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                            rs.getString("objective"), rs.getObject("model_deployment_id", UUID.class),
                            rs.getString("authorization_level"),
                            listOfStrings(rs.getString("context_scopes")), null);
                    return new ExecutionSnapshot(rs.getString("root_agent_code"),
                            rs.getString("root_agent_version"), rs.getString("composition_code"),
                            rs.getString("composition_version"), rs.getString("requested_stage"),
                            rs.getObject("created_by", UUID.class), rs.getString("authorization_watermark"),
                            rs.getString("model_residency_policy"),
                            rs.getObject("model_deployment_id", UUID.class),
                            rs.getObject("external_processing_approval_id", UUID.class),
                            rs.getObject("assistant_policy_config_id", UUID.class),
                            rs.getObject("assistant_policy_row_version", Long.class),
                            rs.getString("assistant_policy_hash"), rs.getString("assistant_policy_environment"),
                            rs.getInt("attempt"), command);
                }).optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_WORKER_FENCE_LOST", 409,
                        "The worker no longer owns this medical assistant run"));
    }

    private ModelSelection modelById(UUID tenantId, UUID deploymentId) {
        return jdbc.sql("""
                select model_deployment_id, display_name, provider_code, model_code,
                  endpoint_url, api_key_ref, residency_policy from model_deployment
                where tenant_id = :tenant and model_deployment_id = :deployment
                  and status = 'ACTIVE' and evaluation_status = 'APPROVED'
                  and connection_status = 'READY' and test_only = false
                  and (:allow_synthetic_test_models or endpoint_url not like 'https://%.example/%')
                  and exists (
                    select 1 from model_evaluation evaluation
                    where evaluation.tenant_id = model_deployment.tenant_id
                      and evaluation.model_deployment_id = model_deployment.model_deployment_id
                      and evaluation.status = 'PASSED'
                  )
                """).param("tenant", tenantId).param("deployment", deploymentId)
                .param("allow_synthetic_test_models", syntheticTestModelsEnabled())
                .query((rs, row) -> new ModelSelection(
                        rs.getObject("model_deployment_id", UUID.class), rs.getString("display_name"),
                        rs.getString("provider_code"), rs.getString("model_code"),
                        rs.getString("endpoint_url"), rs.getString("api_key_ref"),
                        rs.getString("residency_policy")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_MODEL_UNAVAILABLE", 409,
                        "The pinned model service is no longer available"));
    }

    private UUID startChild(UUID tenantId, UUID runId, NodeRow node) {
        return jdbc.sql("""
                insert into medical_agent_child_run(
                  tenant_id, child_run_id, root_run_id, child_agent_code, child_agent_version,
                  state, critical, contribution, source_references, started_at, completed_at)
                values (:tenant, :child, :root, :code, :version, 'RUNNING', :critical,
                  '{}'::jsonb, '[]'::jsonb, now(), null)
                on conflict (tenant_id, root_run_id, child_agent_code) do update set
                  state = 'RUNNING', critical = excluded.critical, contribution = '{}'::jsonb,
                  source_references = '[]'::jsonb, started_at = now(), completed_at = null
                returning child_run_id
                """).param("tenant", tenantId).param("child", UUID.randomUUID()).param("root", runId)
                .param("code", node.agentCode()).param("version", node.agentVersion())
                .param("critical", node.critical()).query(UUID.class).single();
    }

    private void completeChild(UUID tenantId, UUID childRunId, String state,
            Map<String, Object> contribution, List<Map<String, Object>> sourceRefs) {
        jdbc.sql("""
                update medical_agent_child_run set state = :state,
                  contribution = cast(:contribution as jsonb), source_references = cast(:sources as jsonb),
                  completed_at = now()
                where tenant_id = :tenant and child_run_id = :child
                """).param("state", state).param("contribution", json(contribution))
                .param("sources", json(sourceRefs)).param("tenant", tenantId).param("child", childRunId).update();
    }

    private void cancelChild(UUID tenantId, UUID childRunId) {
        jdbc.sql("""
                update medical_agent_child_run set state = 'CANCELLED', completed_at = now()
                where tenant_id = :tenant and child_run_id = :child and state = 'RUNNING'
                """).param("tenant", tenantId).param("child", childRunId).update();
    }

    private boolean cancellationRequested(UUID tenantId, UUID runId, UUID workerId) {
        return jdbc.sql("""
                select count(*) from medical_agent_run where tenant_id = :tenant and run_id = :run
                  and state = 'RUNNING' and worker_lease_owner = :worker and cancel_requested_at is not null
                """).param("tenant", tenantId).param("run", runId).param("worker", workerId)
                .query(Long.class).single() == 1;
    }

    private void finalizeCancellation(
            ClinicalIdentity identity, UUID runId, UUID workerId, String checkpoint) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    update medical_agent_child_run set state = 'CANCELLED', completed_at = now()
                    where tenant_id = :tenant and root_run_id = :run and state in ('QUEUED','RUNNING')
                    """).param("tenant", identity.tenantId()).param("run", runId).update();
            Optional<Long> sequence = jdbc.sql("""
                    update medical_agent_run set state = 'CANCELLED', completed_at = now(),
                      worker_lease_owner = null, worker_lease_until = null, failure_code = null,
                      sequence = sequence + 1, row_version = row_version + 1
                    where tenant_id = :tenant and run_id = :run and state = 'RUNNING'
                      and worker_lease_owner = :worker and cancel_requested_at is not null
                    returning sequence
                    """).param("tenant", identity.tenantId()).param("run", runId).param("worker", workerId)
                    .query(Long.class).optional();
            sequence.ifPresent(value -> insertEvent(identity.tenantId(), runId, value, null,
                    "RunCancelled", Map.of("checkpoint", checkpoint, "state", "CANCELLED")));
        });
    }

    private Map<String, Object> output(
            ExecutionSnapshot snapshot, CreateRunCommand command, ModelSelection model,
            List<Map<String, Object>> contributions, ContextFacts facts,
            boolean criticalFailure, boolean partial, String executionMode,
            long promptTokens, long completionTokens, long totalTokens,
            int modelRequestCount, long modelDurationMs, int toolCallCount) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("candidate_only", true);
        output.put("root_agent_code", snapshot.rootAgentCode());
        output.put("stage_code", command.stageCode());
        output.put("objective", command.objective().trim());
        output.put("summary", criticalFailure
                ? "Eva 未能完成本次医助任务，关键诊疗环节执行失败，请检查模型或工具配置后重试。"
                : partial ? "Eva 已完成部分医助任务，尚有诊疗环节需要人工处理。"
                : "Eva 已汇总 " + contributions.size() + " 位医助的处理结果，等待医生审阅。");
        output.put("model_deployment_id", model.deploymentId());
        output.put("model_display_name", model.displayName());
        output.put("model_code", model.modelCode());
        output.put("provider_code", model.providerCode());
        output.put("execution_mode", executionMode);
        output.put("authorization_level", command.authorizationLevel());
        output.put("context_scopes", command.contextScopes());
        output.put("context_counts", Map.of("documents", facts.documentCount(), "results", facts.resultCount(),
                "orders", facts.orderCount(), "open_tasks", facts.openTaskCount(),
                "attachments", facts.attachmentCount(), "open_critical_values", facts.openCriticalCount()));
        output.put("contributions", contributions);
        output.put("model_usage", Map.of("prompt_tokens", promptTokens,
                "completion_tokens", completionTokens, "total_tokens", totalTokens,
                "request_count", modelRequestCount, "duration_ms", modelDurationMs));
        output.put("tool_call_count", toolCallCount);
        output.put("attempt", snapshot.attempt());
        output.put("warnings", List.of("本结果为 AI 医助候选，不自动写入病历、诊断、医嘱、结果或任务终态。"));
        return output;
    }

    private RunControlHead runControlHead(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                select state, row_version from medical_agent_run
                where tenant_id = :tenant and run_id = :run for update
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RunControlHead(rs.getString("state"), rs.getLong("row_version")))
                .optional().orElseThrow(MedicalAgentHarnessService::contextDenied);
    }

    private void ensureRuntimeLease(
            ClinicalIdentity identity, RetryRunCommand command, UUID patientId, UUID encounterId) {
        long valid = jdbc.sql("""
                select count(*) from context_lease where tenant_id = :tenant and lease_id = :lease
                  and organization_id = :organization and facility_id = :facility and user_id = :user
                  and patient_id = :patient and encounter_id = :encounter
                  and revoked_at is null and expires_at > now()
                """).param("tenant", identity.tenantId()).param("lease", command.contextLeaseId())
                .param("organization", command.organizationId()).param("facility", command.facilityId())
                .param("user", identity.userId()).param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (valid != 1) throw contextDenied();
    }

    private static String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 2 || normalized.length() > 500) {
            throw new AgentRunException("MEDICAL_AGENT_RUN_REASON_INVALID", 400,
                    "The cancellation reason must contain 2 to 500 characters");
        }
        return normalized;
    }

    private static String errorCode(RuntimeException failure) {
        return failure instanceof ModelProviderUnavailableException unavailable ? unavailable.code()
                : failure instanceof AgentRunException agentFailure ? agentFailure.code()
                : "MEDICAL_AGENT_WORKER_FAILED";
    }

    private static void ensureModelResidency(String leasePolicy, String deploymentPolicy) {
        boolean allowed = switch (leasePolicy) {
            case "APPROVED_EXTERNAL" -> Set.of("ON_PREM_ONLY", "LOCAL_PREFERRED", "CLOUD_ALLOWED")
                    .contains(deploymentPolicy);
            case "CN_REGION_ONLY" -> Set.of("ON_PREM_ONLY", "LOCAL_PREFERRED").contains(deploymentPolicy);
            case "ON_PREM_ONLY" -> "ON_PREM_ONLY".equals(deploymentPolicy);
            default -> false;
        };
        if (!allowed) {
            throw new AgentRunException("MODEL_RESIDENCY_DENIED", 403,
                    "The selected model does not satisfy the medical-agent context residency policy");
        }
    }

    private ModelSelection resolveModel(UUID tenantId, UUID requestedDeploymentId) {
        String requested = requestedDeploymentId == null ? "" : " and model_deployment_id = :deployment";
        String liveOnly = syntheticTestModelsEnabled() ? "" : " and endpoint_url not like 'https://%.example/%'";
        JdbcClient.StatementSpec query = jdbc.sql("""
                select model_deployment_id, display_name, provider_code, model_code,
                  endpoint_url, api_key_ref, residency_policy from model_deployment
                where tenant_id = :tenant and status = 'ACTIVE' and evaluation_status = 'APPROVED'
                  and connection_status = 'READY' and test_only = false
                  and exists (
                    select 1 from model_evaluation evaluation
                    where evaluation.tenant_id = model_deployment.tenant_id
                      and evaluation.model_deployment_id = model_deployment.model_deployment_id
                      and evaluation.status = 'PASSED'
                  )
                """ + requested + liveOnly + " order by updated_at desc, model_deployment_id limit 1")
                .param("tenant", tenantId);
        if (requestedDeploymentId != null) query = query.param("deployment", requestedDeploymentId);
        return query.query((rs, row) -> new ModelSelection(
                        rs.getObject("model_deployment_id", UUID.class), rs.getString("display_name"),
                        rs.getString("provider_code"), rs.getString("model_code"),
                        rs.getString("endpoint_url"), rs.getString("api_key_ref"),
                        rs.getString("residency_policy")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_MODEL_UNAVAILABLE", 409,
                        requestedDeploymentId == null
                                ? "No approved and connected model service is available"
                                : "The selected model service is not approved, connected or active"));
    }

    private boolean syntheticTestModelsEnabled() {
        return environment.getProperty("haoemr.medical-agent.synthetic-test-models-enabled", Boolean.class, false);
    }

    private ExternalProcessingApproval externalProcessingApproval(
            UUID tenantId, ModelSelection model, List<String> requestedScopes, UUID pinnedApprovalId) {
        if (!"CLOUD_ALLOWED".equals(model.residencyPolicy())) {
            if (pinnedApprovalId != null) {
                throw new AgentRunException("MODEL_PROCESSING_APPROVAL_MISMATCH", 409,
                        "The pinned external-processing approval does not match the model residency policy");
            }
            return new ExternalProcessingApproval(null);
        }
        String pinned = pinnedApprovalId == null ? "" : " and approval_id = :approval";
        JdbcClient.StatementSpec query = jdbc.sql("""
                select approval_id from medical_ai_external_processing_approval
                where tenant_id = :tenant and model_deployment_id = :deployment
                  and status = 'ACTIVE' and expires_at > now()
                  and allowed_context_scopes @> cast(:scopes as text[])
                """ + pinned + " order by approved_at desc limit 1")
                .param("tenant", tenantId).param("deployment", model.deploymentId())
                .param("scopes", "{" + String.join(",", requestedScopes) + "}");
        if (pinnedApprovalId != null) query = query.param("approval", pinnedApprovalId);
        UUID approvalId = query.query(UUID.class).optional().orElseThrow(() -> new AgentRunException(
                "MODEL_EXTERNAL_PROCESSING_NOT_APPROVED", 409,
                "The cloud model has no active data-processing approval covering the requested context scopes"));
        return new ExternalProcessingApproval(approvalId);
    }

    @SuppressWarnings("unchecked")
    private RuntimePolicy runtimePolicy(UUID tenantId) {
        List<RuntimePolicyRow> active = jdbc.sql("""
                select config_id, row_version, payload::text from config_item
                where tenant_id = :tenant and config_type = 'AI_ASSISTANT_POLICY' and status = 'ACTIVE'
                order by updated_at desc, config_id
                """).param("tenant", tenantId).query((rs, row) -> new RuntimePolicyRow(
                        rs.getObject("config_id", UUID.class), rs.getLong("row_version"),
                        rs.getString("payload"))).list();
        if (active.size() != 1) {
            throw new AgentRunException("MEDICAL_AGENT_POLICY_NOT_PUBLISHED", 409,
                    "Exactly one active Eva work policy is required before a medical assistant task can start");
        }
        RuntimePolicyRow row = active.getFirst();
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(row.payloadJson(), Map.class);
        } catch (Exception invalid) {
            throw new AgentRunException("MEDICAL_AGENT_POLICY_INVALID", 409,
                    "The active Eva work policy payload is invalid");
        }
        String policyEnvironment = String.valueOf(payload.getOrDefault("environment", "")).trim();
        String modelPolicy = String.valueOf(payload.getOrDefault("model_policy", "")).trim();
        Object allowedSources = payload.get("allowed_sources");
        int mainCount = number(payload.get("main_agent_count"));
        int childCount = number(payload.get("child_agent_count"));
        int rateLimit = number(payload.get("rate_limit"));
        int publishedMainCount = activeAgentCount("MAIN");
        int publishedChildCount = activeAgentCount("CHILD");
        boolean valid = Boolean.TRUE.equals(payload.get("approval_required"))
                && !policyEnvironment.isBlank()
                && Set.of("TENANT_ACTIVE_MODEL_WITH_LOCAL_FALLBACK", "TENANT_APPROVED_MODEL_ONLY")
                        .contains(modelPolicy)
                && allowedSources instanceof List<?> sources && !sources.isEmpty()
                && mainCount == publishedMainCount && childCount == publishedChildCount
                && rateLimit >= 1 && rateLimit <= 1000;
        if (!valid || (environment.acceptsProfiles(Profiles.of("prod"))
                && !"production".equalsIgnoreCase(policyEnvironment))) {
            throw new AgentRunException("MEDICAL_AGENT_POLICY_INVALID", 409,
                    "The active Eva work policy does not match the published medical assistants or environment");
        }
        return new RuntimePolicy(row.configId(), row.rowVersion(), sha256(row.payloadJson()), policyEnvironment,
                rateLimit);
    }

    private void ensurePolicyRateLimit(ClinicalIdentity identity, RuntimePolicy policy) {
        long recent = jdbc.sql("""
                select count(*) from medical_agent_run
                where tenant_id = :tenant and created_by = :actor
                  and created_at >= now() - interval '1 hour' and state <> 'CANCELLED'
                """).param("tenant", identity.tenantId()).param("actor", identity.userId())
                .query(Long.class).single();
        if (recent >= policy.maxRunsPerHour()) {
            throw new AgentRunException("MEDICAL_AGENT_RATE_LIMIT_EXCEEDED", 429,
                    "The Eva work policy hourly task limit has been reached");
        }
    }

    private void ensureRuntimePolicyStillActive(UUID tenantId, ExecutionSnapshot snapshot) {
        if (snapshot.assistantPolicyConfigId() == null || snapshot.assistantPolicyRowVersion() == null
                || snapshot.assistantPolicyHash() == null) {
            throw new AgentRunException("MEDICAL_AGENT_POLICY_SNAPSHOT_MISSING", 409,
                    "The medical assistant task has no immutable Eva work-policy snapshot");
        }
        RuntimePolicyRow current = jdbc.sql("""
                select config_id, row_version, payload::text from config_item
                where tenant_id = :tenant and config_id = :config
                  and config_type = 'AI_ASSISTANT_POLICY' and status = 'ACTIVE'
                """).param("tenant", tenantId).param("config", snapshot.assistantPolicyConfigId())
                .query((rs, row) -> new RuntimePolicyRow(rs.getObject("config_id", UUID.class),
                        rs.getLong("row_version"), rs.getString("payload")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_POLICY_RETIRED", 409,
                        "The Eva work policy pinned by this task is no longer active"));
        if (current.rowVersion() != snapshot.assistantPolicyRowVersion()
                || !sha256(current.payloadJson()).equals(snapshot.assistantPolicyHash())) {
            throw new AgentRunException("MEDICAL_AGENT_POLICY_CHANGED", 409,
                    "The Eva work policy changed after this task was queued; create a new task");
        }
    }

    private int activeAgentCount(String level) {
        return jdbc.sql("""
                select count(*) from medical_agent_release
                where status = 'ACTIVE' and agent_level = :level
                """).param("level", level).query(Long.class).single().intValue();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private ActiveBudget ensureGovernanceReady(UUID tenantId, String mainAgentCode) {
        UUID registryId = jdbc.sql("""
                select agent_registry_id from agent_registry
                where tenant_id = :tenant and agent_code = :code and status = 'ACTIVE'
                order by updated_at desc limit 1
                """).param("tenant", tenantId).param("code", mainAgentCode)
                .query(UUID.class).optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_DISABLED", 409, "The selected medical assistant team is not active"));
        List<DependencyRow> dependencies = jdbc.sql("""
                select dependency_type, dependency_code from agent_dependency
                where tenant_id = :tenant and agent_registry_id = :registry
                order by dependency_type, dependency_code
                """).param("tenant", tenantId).param("registry", registryId)
                .query((rs, row) -> new DependencyRow(
                        rs.getString("dependency_type"), rs.getString("dependency_code"))).list();
        for (DependencyRow dependency : dependencies) {
            String table = "SKILL".equals(dependency.type()) ? "skill_registry" : "tool_registry";
            String codeColumn = "SKILL".equals(dependency.type()) ? "skill_code" : "tool_code";
            long active = jdbc.sql("select count(*) from " + table
                            + " where tenant_id = :tenant and " + codeColumn + " = :code and status = 'ACTIVE'")
                    .param("tenant", tenantId).param("code", dependency.code()).query(Long.class).single();
            if (active == 0) {
                throw new AgentRunException("MEDICAL_AGENT_DEPENDENCY_DISABLED", 409,
                        "A required medical assistant capability or tool is inactive: " + dependency.code());
            }
        }
        long passedEvaluation = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type = 'AGENT_EVAL' and status = 'ACTIVE'
                  and payload ->> 'target_agent' = :agent
                  and coalesce((payload ->> 'measured_score')::numeric, 0)
                      >= coalesce((payload ->> 'pass_threshold')::numeric, 1)
                  and payload ->> 'release_gate' = 'PASSED'
                """).param("tenant", tenantId).param("agent", mainAgentCode).query(Long.class).single();
        if (passedEvaluation == 0) {
            throw new AgentRunException("MEDICAL_AGENT_EVALUATION_BLOCKED", 409,
                    "The selected medical assistant team has no active passed release evaluation");
        }
        String budgetCode = "BUDGET_" + mainAgentCode;
        return jdbc.sql("""
                select budget_id, budget_code, max_tokens, max_duration_seconds from agent_run_budget
                where tenant_id = :tenant and budget_code = :budget and status = 'ACTIVE'
                order by updated_at desc limit 1
                """).param("tenant", tenantId).param("budget", budgetCode)
                .query((rs, row) -> new ActiveBudget(
                        rs.getObject("budget_id", UUID.class), rs.getString("budget_code"),
                        rs.getLong("max_tokens"), rs.getInt("max_duration_seconds")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_BUDGET_DISABLED", 409,
                        "The selected medical assistant team has no active processing quota"));
    }

    private static boolean isBudgetFailure(RuntimeException failure) {
        return failure instanceof AgentRunException agentFailure
                && ("MEDICAL_AGENT_BUDGET_TOKENS_EXCEEDED".equals(agentFailure.code())
                || "MEDICAL_AGENT_BUDGET_DURATION_EXCEEDED".equals(agentFailure.code()));
    }

    private static int maxOutputTokens(ActiveBudget budget, long runStarted, long consumedTokens) {
        enforceRunBudget(budget, runStarted, consumedTokens);
        long remaining = budget.maxTokens() - consumedTokens;
        return (int) Math.min(4096, Math.max(1, remaining));
    }

    private static void enforceRunBudget(ActiveBudget budget, long runStarted, long consumedTokens) {
        if (consumedTokens >= budget.maxTokens()) {
            throw new AgentRunException("MEDICAL_AGENT_BUDGET_TOKENS_EXCEEDED", 409,
                    "The medical assistant task reached its configured token limit");
        }
        long elapsedMs = Math.max(0, (System.nanoTime() - runStarted) / 1_000_000);
        if (elapsedMs > budget.maxDurationSeconds() * 1000L) {
            throw new AgentRunException("MEDICAL_AGENT_BUDGET_DURATION_EXCEEDED", 409,
                    "The medical assistant task exceeded its configured duration limit");
        }
    }

    RunContext context(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                select lease.organization_id, lease.facility_id, run.patient_id, run.encounter_id
                from medical_agent_run run join context_lease lease
                  on lease.tenant_id = run.tenant_id and lease.lease_id = run.context_lease_id
                where run.tenant_id = :tenant and run.run_id = :run
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> new RunContext(rs.getObject("organization_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class)))
                .optional().orElseThrow(MedicalAgentHarnessService::contextDenied);
    }

    private LeaseRow lease(ClinicalIdentity identity, CreateRunCommand command) {
        return jdbc.sql("""
                select authorization_watermark, model_residency_policy from context_lease
                where tenant_id = :tenant and lease_id = :lease and organization_id = :organization
                  and facility_id = :facility and user_id = :user
                  and patient_id is not distinct from :patient
                  and encounter_id is not distinct from :encounter
                  and revoked_at is null and expires_at > now()
                """).param("tenant", identity.tenantId()).param("lease", command.contextLeaseId())
                .param("organization", command.organizationId()).param("facility", command.facilityId())
                .param("user", identity.userId()).param("patient", command.patientId())
                .param("encounter", command.encounterId())
                .query((rs, row) -> new LeaseRow(rs.getString("authorization_watermark"),
                        rs.getString("model_residency_policy")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "CONTEXT_NOT_PERMITTED", 403, "The medical-agent context lease is invalid or expired"));
    }

    private void validateTarget(UUID tenantId, CreateRunCommand command) {
        if (command.targetType() == null && command.targetId() == null) {
            return; // 通用问答：未绑定患者与诊疗目标，跳过目标归属校验
        }
        boolean matchesContext = switch (command.targetType()) {
            case "ENCOUNTER" -> command.targetId().equals(command.encounterId());
            case "DOCUMENT" -> targetExists("clinical_document", "document_id", tenantId, command);
            case "RESULT" -> targetExists("clinical_result", "result_id", tenantId, command);
            case "TASK" -> targetExists("clinical_task", "task_id", tenantId, command);
            case "CARE_PLAN" -> targetExists("nursing_care_plan", "care_plan_id", tenantId, command);
            default -> false;
        };
        if (!matchesContext) {
            throw new AgentRunException("TARGET_CONTEXT_MISMATCH", 403,
                    "The selected target does not belong to the leased patient and encounter context");
        }
    }

    private boolean targetExists(String table, String idColumn, UUID tenantId, CreateRunCommand command) {
        return jdbc.sql("select count(*) from " + table
                        + " where tenant_id = :tenant and " + idColumn + " = :target"
                        + " and patient_id = :patient and encounter_id = :encounter")
                .param("tenant", tenantId).param("target", command.targetId())
                .param("patient", command.patientId()).param("encounter", command.encounterId())
                .query(Long.class).single() == 1;
    }

    private MainReleaseRow main(String agentCode) {
        return jdbc.sql("""
                select release.agent_code, release.release_version, composition.composition_code,
                  composition.release_version as composition_version
                from medical_agent_release release
                join medical_agent_composition_release composition
                  on composition.root_agent_code = release.agent_code
                  and composition.release_version = release.release_version
                where release.agent_code = :code and release.agent_level = 'MAIN'
                  and release.status = 'ACTIVE' and composition.status = 'ACTIVE'
                """).param("code", agentCode)
                .query((rs, row) -> new MainReleaseRow(rs.getString("agent_code"),
                        rs.getString("release_version"), rs.getString("composition_code"),
                        rs.getString("composition_version")))
                .optional().orElseThrow(() -> new AgentRunException(
                        "AGENT_RELEASE_NOT_ACTIVE", 409, "The selected main-agent release is not active"));
    }

    private List<NodeRow> nodes(String compositionCode, String compositionVersion, String stageCode) {
        String stagePredicate = "ALL".equals(stageCode) ? "" : " and node.stage_code = :stage";
        JdbcClient.StatementSpec query = jdbc.sql("""
                select node.child_agent_code, release.release_version, release.display_name,
                  release.display_role, release.current_action, release.contribution_label,
                  release.output_schema, node.critical
                from medical_agent_composition_node node
                join medical_agent_release release on release.agent_code = node.child_agent_code
                  and release.release_version = node.release_version
                where node.composition_code = :composition and node.release_version = :composition_version
                """ + stagePredicate + " order by node.node_order").param("composition", compositionCode)
                .param("composition_version", compositionVersion);
        if (!"ALL".equals(stageCode)) query = query.param("stage", stageCode);
        return query.query((rs, row) -> new NodeRow(rs.getString("child_agent_code"),
                rs.getString("release_version"), rs.getString("display_name"), rs.getString("display_role"),
                rs.getString("current_action"), rs.getString("contribution_label"),
                rs.getString("output_schema"), rs.getBoolean("critical"))).list();
    }

    private ContextFacts contextFacts(List<MedicalAgentToolGateway.ToolResult> tools) {
        long documents = itemCount(tools, "CLINICAL_DOCUMENT_READ");
        long orders = itemCount(tools, "CLINICAL_ORDER_READ");
        long results = itemCount(tools, "CLINICAL_RESULT_READ");
        long tasks = itemCount(tools, "CLINICAL_TASK_READ");
        long attachments = itemCount(tools, "CLINICAL_ATTACHMENT_READ");
        long critical = tools.stream().filter(tool -> "CLINICAL_RESULT_READ".equals(tool.toolCode()))
                .flatMap(tool -> tool.items().stream())
                .filter(item -> Boolean.TRUE.equals(item.get("open_critical"))).count();
        List<Map<String, Object>> references = tools.stream()
                .flatMap(tool -> tool.sourceReferences().stream()).toList();
        return new ContextFacts(documents, orders, results, tasks, attachments, critical, references);
    }

    private static long itemCount(List<MedicalAgentToolGateway.ToolResult> tools, String code) {
        return tools.stream().filter(tool -> code.equals(tool.toolCode()))
                .mapToLong(tool -> tool.items().size()).sum();
    }

    private Map<String, Object> contribution(
            NodeRow node,
            CreateRunCommand command,
            MedicalAgentModelGateway.ModelResult generated,
            List<MedicalAgentToolGateway.ToolResult> tools) {
        Map<String, Object> contribution = new LinkedHashMap<>();
        contribution.put("agent_code", node.agentCode());
        contribution.put("display_name", node.displayName());
        contribution.put("role", node.displayRole());
        contribution.put("action", node.currentAction());
        contribution.put("contribution_label", node.contributionLabel());
        contribution.put("output_schema", node.outputSchema());
        contribution.put("skill", MedicalAgentSkills.skill(node.agentCode()));
        contribution.putAll(generated.output());
        contribution.put("candidate_only", true);
        contribution.put("execution_mode", generated.executionMode());
        contribution.put("model_request_id", generated.requestId());
        contribution.put("model_usage", Map.of("prompt_tokens", generated.promptTokens(),
                "completion_tokens", generated.completionTokens(), "total_tokens", generated.totalTokens(),
                "duration_ms", generated.durationMs()));
        contribution.put("tools", tools.stream().map(tool -> Map.of(
                "tool_code", tool.toolCode(), "tool_version", tool.toolVersion(),
                "invocation_id", tool.invocationId(), "item_count", tool.items().size(),
                "duration_ms", tool.durationMs())).toList());
        contribution.put("source_references", tools.stream()
                .flatMap(tool -> tool.sourceReferences().stream()).toList());
        contribution.put("objective", command.objective().trim());
        contribution.put("objective_trust", "UNTRUSTED_USER_INPUT");
        return contribution;
    }

    private void transition(UUID tenantId, UUID runId, String state, String eventType, Map<String, Object> payload) {
        long sequence = jdbc.sql("""
                update medical_agent_run set state = :state, sequence = sequence + 1,
                  row_version = row_version + 1
                where tenant_id = :tenant and run_id = :run returning sequence
                """).param("state", state).param("tenant", tenantId).param("run", runId)
                .query(Long.class).single();
        insertEvent(tenantId, runId, sequence, null, eventType, payload);
    }

    private void appendEvent(UUID tenantId, UUID runId, UUID childRunId, String eventType, Map<String, Object> payload) {
        long sequence = jdbc.sql("""
                update medical_agent_run set sequence = sequence + 1, row_version = row_version + 1,
                  last_heartbeat_at = case when state = 'RUNNING' then now() else last_heartbeat_at end
                where tenant_id = :tenant and run_id = :run returning sequence
                """).param("tenant", tenantId).param("run", runId).query(Long.class).single();
        insertEvent(tenantId, runId, sequence, childRunId, eventType, payload);
    }

    private void insertEventAtCurrentSequence(UUID tenantId, UUID runId, String eventType, Map<String, Object> payload) {
        long sequence = jdbc.sql("select sequence from medical_agent_run where tenant_id = :tenant and run_id = :run")
                .param("tenant", tenantId).param("run", runId).query(Long.class).single();
        insertEvent(tenantId, runId, sequence, null, eventType, payload);
    }

    private void insertEvent(UUID tenantId, UUID runId, long sequence, UUID childRunId,
            String eventType, Map<String, Object> payload) {
        jdbc.sql("""
                insert into medical_agent_run_event(
                  tenant_id, run_id, sequence, event_id, event_type, child_run_id, payload)
                values (:tenant, :run, :sequence, :event, :type, :child, cast(:payload as jsonb))
                """).param("tenant", tenantId).param("run", runId).param("sequence", sequence)
                .param("event", UUID.randomUUID()).param("type", eventType).param("child", childRunId)
                .param("payload", json(payload)).update();
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AgentRunException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'MEDICAL_AGENT_RUN_CREATE', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AgentRunException("IDEMPOTENCY_REPLAY", 409, "This medical-agent command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID runId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 202,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'MEDICAL_AGENT_RUN_CREATE'
                  and idempotency_key = :key
                """).param("resource", runId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID runId, String rootAgentCode, String state) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|MEDICAL_AGENT_RUN_READY|"
                + runId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, 'MEDICAL_AGENT_RUN_READY',
                  'MEDICAL_AGENT_RUN', :resource, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("resource", runId).param("trace", trace)
                .param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MEDICAL_AGENT_RUN', :run, 1,
                  'MedicalAgentRunReadyForReview', 1,
                  jsonb_build_object('root_agent_code', :root, 'state', :state))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("run", runId).param("root", rootAgentCode).param("state", state).update();
    }

    /**
     * 当请求未显式指定主医助/阶段时，委托 {@link AgentOrchestrator} 按 source_route
     * 做确定性动态编排；显式指定的编码原样保留（允许医生在对话界面手动覆盖）。
     */
    private CreateRunCommand resolveRouting(CreateRunCommand command) {
        boolean explicit = command.mainAgentCode() != null && !command.mainAgentCode().isBlank()
                && command.stageCode() != null && !command.stageCode().isBlank();
        if (explicit) {
            return command;
        }
        AgentOrchestrator.Routing routing = orchestrator.resolve(command.sourceRoute(), command.objective());
        return new CreateRunCommand(command.organizationId(), command.facilityId(), command.patientId(),
                command.encounterId(), command.contextLeaseId(), routing.mainAgentCode(), routing.stageCode(),
                command.targetType(), command.targetId(), command.objective(), command.modelDeploymentId(),
                command.authorizationLevel(), command.contextScopes(), command.sourceRoute());
    }

    private void validate(CreateRunCommand command) {
        if (command.contextLeaseId() == null || command.organizationId() == null || command.facilityId() == null) {
            throw invalid("organization, facility and context lease are required");
        }
        if (command.encounterId() != null && command.patientId() == null) {
            throw invalid("an encounter context requires a patient");
        }
        if (command.mainAgentCode() == null || command.mainAgentCode().isBlank()
                || command.stageCode() == null || command.stageCode().isBlank()) {
            throw invalid("main_agent_code and stage_code are required");
        }
        if (command.targetType() != null && !TARGET_TYPES.contains(command.targetType())) {
            throw invalid("target_type must be ENCOUNTER, DOCUMENT, RESULT, TASK or CARE_PLAN");
        }
        if ((command.targetType() == null) != (command.targetId() == null)) {
            throw invalid("target_type and target_id must be provided together");
        }
        if (command.objective() == null || command.objective().trim().length() < 2
                || command.objective().trim().length() > 1024) {
            throw invalid("objective must contain 2 to 1024 characters");
        }
        if (command.authorizationLevel() == null
                || !AUTHORIZATION_LEVELS.contains(command.authorizationLevel())) {
            throw invalid("authorization_level must be READ_ONLY, STANDARD or EXTENDED");
        }
        if (command.contextScopes() == null || command.contextScopes().isEmpty()
                || command.contextScopes().size() > CONTEXT_SCOPES.size()
                || !CONTEXT_SCOPES.containsAll(command.contextScopes())) {
            throw invalid("context_scopes must contain one or more supported clinical data scopes");
        }
    }

    private AgentReleaseView view(AgentReleaseRow row) {
        return new AgentReleaseView(row.code(), row.version(), row.displayName(), row.level(), row.parentCode(),
                row.stageCode(), row.description(), row.doctorFacingSummary(), row.category(), row.displayRole(),
                row.currentAction(), row.contributionLabel(), row.questionExamples(), row.outputSchema(),
                row.autonomyLevel(), row.maxSteps(), row.maxToolCalls(), row.maxDurationSeconds(), row.usageCount());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored medical-agent JSON is invalid", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), List.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored medical-agent JSON list is invalid", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfStrings(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), List.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored medical-agent question examples are invalid", invalid);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Medical-agent value cannot be serialized", invalid);
        }
    }

    private static AgentRunException invalid(String message) {
        return new AgentRunException("MEDICAL_AGENT_REQUEST_INVALID", 400, message);
    }

    private static AgentRunException contextDenied() {
        return new AgentRunException("CONTEXT_NOT_PERMITTED", 403,
                "The requested medical-agent context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record CreateRunCommand(UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId,
            UUID contextLeaseId, String mainAgentCode, String stageCode, String targetType,
            UUID targetId, String objective, UUID modelDeploymentId, String authorizationLevel,
            List<String> contextScopes, String sourceRoute) {}

    record RetryRunCommand(UUID organizationId, UUID facilityId, UUID contextLeaseId, long expectedRowVersion) {}

    record WorkerClaim(UUID tenantId, UUID runId, int attempt, long sequence) {}

    record AgentFamilyView(AgentReleaseView mainAgent, List<AgentReleaseView> childAgents) {}

    record AgentReleaseView(String agentCode, String releaseVersion, String displayName, String agentLevel,
            String parentAgentCode, String stageCode, String description, String doctorFacingSummary,
            String category, String displayRole, String currentAction, String contributionLabel,
            List<String> questionExamples, String outputSchema, String autonomyLevel,
            int maxSteps, int maxToolCalls, int maxDurationSeconds, int usageCount) {}

    record RunView(UUID runId, UUID contextLeaseId, String rootAgentCode, String rootAgentVersion,
            String compositionCode, String compositionVersion, String requestedStage, UUID patientId,
            UUID encounterId, String targetType, UUID targetId, String objective, String state, long sequence,
            Map<String, Object> output, OffsetDateTime createdAt, OffsetDateTime completedAt, long rowVersion,
            int attempt, int maxAttempts, OffsetDateTime cancelRequestedAt, String failureCode,
            List<ChildRunView> childRuns, List<RunEventView> events) {}

    record ChildRunView(UUID childRunId, String childAgentCode, String displayName, String displayRole,
            String currentAction, String contributionLabel, String state, boolean critical,
            Map<String, Object> contribution, List<Map<String, Object>> sourceReferences,
            OffsetDateTime startedAt, OffsetDateTime completedAt) {}

    record RunEventView(long sequence, String eventType, UUID childRunId, Map<String, Object> payload,
            OffsetDateTime occurredAt) {}

    record RunContext(UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {}
    record OperationsRunView(UUID runId, String rootAgentCode, String rootAgentName, String requestedStage,
            String state, String modelDisplayName, String providerCode, String authorizationLevel,
            long modelTotalTokens, long actualDurationMs, int modelRequestCount, int toolCallCount,
            int toolFailureCount, int attempt, int maxAttempts, String failureCode,
            OffsetDateTime createdAt, OffsetDateTime completedAt, boolean externalProcessingApproved,
            String assistantPolicyEnvironment) {}
    record OperationsToolInvocationView(UUID invocationId, UUID childRunId, String toolCode, String toolVersion,
            int itemCount, String outcome, long durationMs, String errorCode,
            OffsetDateTime invokedAt, OffsetDateTime completedAt) {}
    private record DependencyRow(String type, String code) {}
    private record ActiveBudget(UUID budgetId, String budgetCode, long maxTokens, int maxDurationSeconds) {}

    private record AgentReleaseRow(String code, String version, String displayName, String level,
            String parentCode, String stageCode, String description, String doctorFacingSummary,
            String category, String displayRole, String currentAction,
            String contributionLabel, String outputSchema, String autonomyLevel, int maxSteps,
            int maxToolCalls, int maxDurationSeconds, int usageCount, List<String> questionExamples) {}
    private record MainReleaseRow(String agentCode, String agentVersion, String compositionCode,
            String compositionVersion) {}
    private record NodeRow(String agentCode, String agentVersion, String displayName, String displayRole,
            String currentAction, String contributionLabel, String outputSchema, boolean critical) {}
    private record LeaseRow(String watermark, String residencyPolicy) {}
    private record ContextFacts(long documentCount, long orderCount, long resultCount, long openTaskCount,
            long attachmentCount, long openCriticalCount, List<Map<String, Object>> sourceReferences) {
        static ContextFacts empty() { return new ContextFacts(0, 0, 0, 0, 0, 0, List.of()); }
        long totalCount() { return documentCount + orderCount + resultCount + openTaskCount + attachmentCount; }
    }
    private record ModelSelection(UUID deploymentId, String displayName, String providerCode,
            String modelCode, String endpointUrl, String apiKeyReference, String residencyPolicy) {}
    private record ExternalProcessingApproval(UUID approvalId) {}
    private record RuntimePolicy(UUID configId, long rowVersion, String payloadHash, String policyEnvironment,
            int maxRunsPerHour) {}
    private record RuntimePolicyRow(UUID configId, long rowVersion, String payloadJson) {}
    private record ExecutionSnapshot(String rootAgentCode, String rootAgentVersion,
            String compositionCode, String compositionVersion, String stageCode, UUID createdBy,
            String watermark, String modelResidencyPolicy, UUID modelDeploymentId,
            UUID externalProcessingApprovalId, UUID assistantPolicyConfigId, Long assistantPolicyRowVersion,
            String assistantPolicyHash, String assistantPolicyEnvironment,
            int attempt, CreateRunCommand command) {}
    private record ExpiredRun(UUID tenantId, UUID runId, String state, long sequence, int attempt) {}
    private record WorkerFailureHead(int attempt, int maxAttempts, boolean cancellation) {}
    private record RunControlHead(String state, long rowVersion) {}
    private record RunRetryHead(String state, long rowVersion, UUID patientId, UUID encounterId) {}
    private record RootRunRow(UUID runId, UUID contextLeaseId, String rootAgentCode, String rootAgentVersion,
            String compositionCode, String compositionVersion, String stageCode, UUID patientId, UUID encounterId,
            String targetType, UUID targetId, String objective, String state, long sequence, String output,
            OffsetDateTime createdAt, OffsetDateTime completedAt, long rowVersion, int attempt, int maxAttempts,
            OffsetDateTime cancelRequestedAt, String failureCode) {}
}
