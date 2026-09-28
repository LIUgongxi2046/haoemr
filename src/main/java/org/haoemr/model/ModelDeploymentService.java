package org.haoemr.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.haoemr.contracts.ModelDeploymentDeactivateRequestWire;
import org.haoemr.contracts.ModelDeploymentConnectionTestRequestWire;
import org.haoemr.contracts.ModelDeploymentPublishRequestWire;
import org.haoemr.contracts.ModelDeploymentRegisterRequestWire;
import org.haoemr.contracts.ModelDeploymentUpdateRequestWire;
import org.haoemr.contracts.ModelDeploymentWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ModelDeploymentService {
    private static final Pattern SECRET_REFERENCE = Pattern.compile(
            "^(env://[A-Z][A-Z0-9_]{2,127}|file:///\\S+)$");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ModelConnectionVerifier connectionVerifier;
    private final ManagedModelSecretStore secretStore;

    ModelDeploymentService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ModelConnectionVerifier connectionVerifier,
            ManagedModelSecretStore secretStore) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.connectionVerifier = connectionVerifier;
        this.secretStore = secretStore;
    }

    ModelDeploymentWire register(
            ClinicalIdentity identity, String idempotencyKey, ModelDeploymentRegisterRequestWire request) {
        if (request.providerCode() == null || request.providerCode().isBlank()
                || request.displayName() == null || request.displayName().isBlank()
                || request.residencyPolicy() == null) {
            throw invalid("provider_code, display_name and residency_policy are required");
        }
        String modelCode = (request.modelCode() == null || request.modelCode().isBlank())
                ? generatedModelCode()
                : request.modelCode().trim();
        String endpointUrl = normalizeEndpoint(request.endpointUrl());
        rejectRetiredSyntheticConfiguration(request.providerCode(), endpointUrl);
        boolean testOnly = false;
        String requestedApiKeyRef = normalizeSecretReference(request.apiKeyRef());
        String rawApiKey = normalizeApiKey(request.apiKey());
        if (requestedApiKeyRef != null && rawApiKey != null) {
            throw invalid("provide api_key or api_key_ref, not both");
        }
        if ((requestedApiKeyRef != null || rawApiKey != null) && endpointUrl == null) {
            throw invalid("endpoint_url is required when an API key is configured");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_REGISTER", idempotencyKey,
                    sha256(modelCode + "|" + request.providerCode() + "|" + request.residencyPolicy()));
            boolean codeExists = jdbc.sql("""
                    select exists(select 1 from model_deployment
                    where tenant_id = :tenant and model_code = :code)
                    """).param("tenant", identity.tenantId()).param("code", modelCode)
                    .query(Boolean.class).single();
            if (codeExists) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_CODE_EXISTS", 409,
                        "模型编码 " + modelCode + " 已存在，请使用不同的模型编码");
            }
            UUID deploymentId = UUID.randomUUID();
            String apiKeyRef = requestedApiKeyRef;
            if (rawApiKey != null) {
                apiKeyRef = secretStore.store(identity.tenantId(), deploymentId, rawApiKey).reference();
                deleteOnRollback(apiKeyRef);
            }
            String connectionStatus = apiKeyRef == null ? "NOT_CONFIGURED" : "UNVERIFIED";
            jdbc.sql("""
                    insert into model_deployment(
                      tenant_id, model_deployment_id, model_code, provider_code, display_name,
                      residency_policy, test_only, endpoint_url, api_key_ref, connection_status, status, evaluation_status)
                    values (:tenant, :deployment, :model_code, :provider_code, :display_name,
                      :residency_policy, :test_only, :endpoint_url, :api_key_ref, :connection_status, 'ACTIVE', 'EVALUATING')
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .param("model_code", modelCode).param("provider_code", request.providerCode())
                    .param("display_name", request.displayName().trim())
                    .param("residency_policy", request.residencyPolicy().name())
                    .param("test_only", testOnly)
                    .param("endpoint_url", endpointUrl).param("api_key_ref", apiKeyRef)
                    .param("connection_status", connectionStatus).update();
            appendEvidence(identity, deploymentId, 1, "MODEL_DEPLOYMENT_REGISTERED", "ModelDeploymentRegistered");
            completeCommand(identity, "MODEL_DEPLOYMENT_REGISTER", idempotencyKey, deploymentId);
            return deployment(identity.tenantId(), deploymentId);
        });
    }

    ModelDeploymentWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId,
            ModelDeploymentDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_DEACTIVATE", idempotencyKey,
                    sha256(deploymentId + "|" + request.expectedRowVersion()));
            DeploymentHead current = jdbc.sql("""
                    select status, row_version, api_key_ref from model_deployment
                    where tenant_id = :tenant and model_deployment_id = :deployment for update
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .query((rs, row) -> new DeploymentHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getString("api_key_ref")))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409, "The model deployment changed; reload before retrying");
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_STATE_INVALID", 409, "Only an active model can be deactivated");
            }
            jdbc.sql("""
                    update model_deployment set status = 'INACTIVE', api_key_ref = null,
                      connection_status = 'NOT_CONFIGURED', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and model_deployment_id = :deployment and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .param("expected", current.rowVersion()).update();
            deleteAfterCommit(current.apiKeyRef());
            appendEvidence(identity, deploymentId, current.rowVersion() + 1,
                    "MODEL_DEPLOYMENT_DEACTIVATED", "ModelDeploymentDeactivated");
            completeCommand(identity, "MODEL_DEPLOYMENT_DEACTIVATE", idempotencyKey, deploymentId);
            return deployment(identity.tenantId(), deploymentId);
        });
    }

    void purge(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId,
            ModelDeploymentDeactivateRequestWire request) {
        transactions.executeWithoutResult(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_PURGE", idempotencyKey,
                    sha256(deploymentId + "|" + request.expectedRowVersion()));
            DeploymentHead current = jdbc.sql("""
                    select status, row_version, api_key_ref from model_deployment
                    where tenant_id = :tenant and model_deployment_id = :deployment for update
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .query((rs, row) -> new DeploymentHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getString("api_key_ref")))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409,
                        "The model deployment changed; reload before retrying");
            }
            if (!"INACTIVE".equals(current.status())) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_STATE_INVALID", 409,
                        "Only an inactive model can be permanently removed; deactivate it first");
            }
            jdbc.sql("""
                    update medical_agent_run set model_deployment_id = null
                    where tenant_id = :tenant and model_deployment_id = :deployment
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId).update();
            jdbc.sql("""
                    update medical_agent_run set external_processing_approval_id = null
                    where tenant_id = :tenant and external_processing_approval_id in (
                      select approval_id from medical_ai_external_processing_approval
                      where tenant_id = :tenant and model_deployment_id = :deployment)
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId).update();
            jdbc.sql("delete from model_evaluation where tenant_id = :tenant and model_deployment_id = :deployment")
                    .param("tenant", identity.tenantId()).param("deployment", deploymentId).update();
            jdbc.sql("delete from medical_ai_external_processing_approval where tenant_id = :tenant and model_deployment_id = :deployment")
                    .param("tenant", identity.tenantId()).param("deployment", deploymentId).update();
            jdbc.sql("delete from model_deployment where tenant_id = :tenant and model_deployment_id = :deployment")
                    .param("tenant", identity.tenantId()).param("deployment", deploymentId).update();
            deleteAfterCommit(current.apiKeyRef());
            appendEvidence(identity, deploymentId, current.rowVersion() + 1,
                    "MODEL_DEPLOYMENT_PURGED", "ModelDeploymentPurged");
            completeCommand(identity, "MODEL_DEPLOYMENT_PURGE", idempotencyKey, deploymentId);
        });
    }

    ModelDeploymentWire publish(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId,
            ModelDeploymentPublishRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_PUBLISH", idempotencyKey,
                    sha256(deploymentId + "|" + request.expectedRowVersion()));
            PublishHead current = jdbc.sql("""
                    select status, connection_status, test_only, row_version from model_deployment
                    where tenant_id = :tenant and model_deployment_id = :deployment for update
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .query((rs, row) -> new PublishHead(rs.getString("status"),
                            rs.getString("connection_status"), rs.getBoolean("test_only"), rs.getLong("row_version")))
                    .optional().orElseThrow(ModelDeploymentService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409,
                        "The model deployment changed; reload before retrying");
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_STATE_INVALID", 409,
                        "Only an active model deployment can be published");
            }
            if (current.testOnly()) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_TEST_ONLY", 409,
                        "A test-only model deployment cannot be published or used for clinical runtime routing");
            }
            if (!"READY".equals(current.connectionStatus())) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_CONNECTION_NOT_READY", 409,
                        "Verify the model connection before publishing");
            }
            jdbc.sql("""
                    update model_deployment set evaluation_status = 'APPROVED',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and model_deployment_id = :deployment and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, deploymentId, current.rowVersion() + 1,
                    "MODEL_DEPLOYMENT_PUBLISHED", "ModelDeploymentPublished");
            completeCommand(identity, "MODEL_DEPLOYMENT_PUBLISH", idempotencyKey, deploymentId);
            return deployment(identity.tenantId(), deploymentId);
        });
    }

    ModelDeploymentWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId,
            ModelDeploymentUpdateRequestWire request) {
        if (request.displayName() == null || request.displayName().trim().length() < 2
                || request.residencyPolicy() == null || request.credentialAction() == null
                || request.expectedRowVersion() == null) {
            throw invalid("display_name, residency_policy, credential_action and expected_row_version are required");
        }
        String endpointUrl = normalizeEndpoint(request.endpointUrl());
        rejectRetiredSyntheticConfiguration(null, endpointUrl);
        String requestedApiKeyRef = normalizeSecretReference(request.apiKeyRef());
        String rawApiKey = normalizeApiKey(request.apiKey());
        if (requestedApiKeyRef != null && rawApiKey != null) {
            throw invalid("provide api_key or api_key_ref, not both");
        }
        if (request.credentialAction() == ModelDeploymentUpdateRequestWire.CredentialActionValue.REPLACE
                && requestedApiKeyRef == null && rawApiKey == null) {
            throw invalid("api_key or api_key_ref is required when credential_action is REPLACE");
        }
        if (request.credentialAction() != ModelDeploymentUpdateRequestWire.CredentialActionValue.REPLACE
                && (requestedApiKeyRef != null || rawApiKey != null)) {
            throw invalid("api_key and api_key_ref are only accepted when credential_action is REPLACE");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_UPDATE", idempotencyKey,
                    sha256(deploymentId + "|" + request.displayName() + "|" + request.residencyPolicy()
                            + "|" + endpointUrl + "|" + request.credentialAction() + "|"
                            + requestedApiKeyRef + "|" + request.expectedRowVersion()));
            DeploymentConfigHead current = jdbc.sql("""
                    select status, row_version, api_key_ref from model_deployment
                    where tenant_id = :tenant and model_deployment_id = :deployment for update
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .query((rs, row) -> new DeploymentConfigHead(
                            rs.getString("status"), rs.getLong("row_version"), rs.getString("api_key_ref")))
                    .optional().orElseThrow(ModelDeploymentService::contextDenied);
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409,
                        "The model deployment changed; reload before retrying");
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_STATE_INVALID", 409,
                        "Only an active model can be updated");
            }
            String replacementApiKeyRef = requestedApiKeyRef;
            if (request.credentialAction() == ModelDeploymentUpdateRequestWire.CredentialActionValue.REPLACE
                    && rawApiKey != null) {
                replacementApiKeyRef = secretStore.store(identity.tenantId(), deploymentId, rawApiKey).reference();
                deleteOnRollback(replacementApiKeyRef);
            }
            String apiKeyRef = switch (request.credentialAction()) {
                case KEEP -> current.apiKeyRef();
                case REPLACE -> replacementApiKeyRef;
                case CLEAR -> null;
            };
            if (apiKeyRef != null && endpointUrl == null) {
                throw invalid("endpoint_url is required when api_key_ref is configured");
            }
            String connectionStatus = apiKeyRef == null ? "NOT_CONFIGURED" : "UNVERIFIED";
            jdbc.sql("""
                    update model_deployment set display_name = :name, residency_policy = :residency,
                      endpoint_url = :endpoint, api_key_ref = :api_key_ref,
                      connection_status = :connection_status, last_connection_tested_at = null,
                      last_connection_latency_ms = null, last_connection_error_code = null,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and model_deployment_id = :deployment and row_version = :expected
                    """).param("name", request.displayName().trim())
                    .param("residency", request.residencyPolicy().name()).param("endpoint", endpointUrl)
                    .param("api_key_ref", apiKeyRef).param("connection_status", connectionStatus)
                    .param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .param("expected", request.expectedRowVersion()).update();
            if (request.credentialAction() != ModelDeploymentUpdateRequestWire.CredentialActionValue.KEEP) {
                deleteAfterCommit(current.apiKeyRef());
            }
            appendEvidence(identity, deploymentId, current.rowVersion() + 1,
                    "MODEL_DEPLOYMENT_UPDATED", "ModelDeploymentUpdated");
            completeCommand(identity, "MODEL_DEPLOYMENT_UPDATE", idempotencyKey, deploymentId);
            return deployment(identity.tenantId(), deploymentId);
        });
    }

    List<ModelDeploymentWire> list(ClinicalIdentity identity) {
        return jdbc.sql("""
                select model_deployment_id from model_deployment
                where tenant_id = :tenant
                order by case when status = 'ACTIVE' then 0 else 1 end,
                  case when connection_status = 'READY' then 0 else 1 end,
                  case when endpoint_url like 'https://%.example/%' then 1 else 0 end,
                  updated_at desc, model_code, model_deployment_id
                """).param("tenant", identity.tenantId()).query(UUID.class).list().stream()
                .map(id -> deployment(identity.tenantId(), id)).toList();
    }

    ModelDeploymentWire testConnection(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID deploymentId,
            ModelDeploymentConnectionTestRequestWire request) {
        if (request.expectedRowVersion() == null) {
            throw invalid("expected_row_version is required");
        }
        ConnectionConfig config = jdbc.sql("""
                select model_code, endpoint_url, api_key_ref, status, row_version
                from model_deployment where tenant_id = :tenant and model_deployment_id = :deployment
                """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                .query((rs, row) -> new ConnectionConfig(rs.getString("model_code"),
                        rs.getString("endpoint_url"), rs.getString("api_key_ref"),
                        rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(ModelDeploymentService::contextDenied);
        if (!"ACTIVE".equals(config.status())) {
            throw new ModelDeploymentException("MODEL_DEPLOYMENT_STATE_INVALID", 409,
                    "Only an active model connection can be tested");
        }
        if (config.rowVersion() != request.expectedRowVersion()) {
            throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409,
                    "The model deployment changed; reload before retrying");
        }
        if (config.endpointUrl() == null || config.apiKeyReference() == null) {
            throw invalid("endpoint_url and api_key_ref are required before testing the connection");
        }
        rejectRetiredSyntheticConfiguration(null, config.endpointUrl());
        ModelConnectionVerifier.ProbeResult probe = connectionVerifier.probe(
                config.modelCode(), config.endpointUrl(), config.apiKeyReference());
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_DEPLOYMENT_CONNECTION_TEST", idempotencyKey,
                    sha256(deploymentId + "|" + config.rowVersion()));
            int updated = jdbc.sql("""
                    update model_deployment set connection_status = :connection_status,
                      last_connection_tested_at = now(), last_connection_latency_ms = :latency,
                      last_connection_error_code = :error, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and model_deployment_id = :deployment
                      and row_version = :expected and status = 'ACTIVE'
                    """).param("connection_status", probe.succeeded() ? "READY" : "FAILED")
                    .param("latency", probe.latencyMs()).param("error", probe.errorCode())
                    .param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .param("expected", config.rowVersion()).update();
            if (updated != 1) {
                throw new ModelDeploymentException("MODEL_DEPLOYMENT_VERSION_CONFLICT", 409,
                        "The model deployment changed during the connection test");
            }
            appendEvidence(identity, deploymentId, config.rowVersion() + 1,
                    probe.succeeded() ? "MODEL_CONNECTION_VERIFIED" : "MODEL_CONNECTION_FAILED",
                    probe.succeeded() ? "ModelConnectionVerified" : "ModelConnectionFailed");
            completeCommand(identity, "MODEL_DEPLOYMENT_CONNECTION_TEST", idempotencyKey, deploymentId);
            return deployment(identity.tenantId(), deploymentId);
        });
    }

    private ModelDeploymentWire deployment(UUID tenantId, UUID deploymentId) {
        return jdbc.sql("""
                select model_deployment_id, model_code, provider_code, display_name, residency_policy, test_only,
                  endpoint_url, status, evaluation_status, api_key_ref, connection_status,
                  last_connection_tested_at, last_connection_latency_ms, last_connection_error_code, row_version
                  , exists(
                    select 1 from model_evaluation evaluation
                    where evaluation.tenant_id = model_deployment.tenant_id
                      and evaluation.model_deployment_id = model_deployment.model_deployment_id
                      and evaluation.status = 'PASSED'
                  ) as release_evidence_ready
                from model_deployment where tenant_id = :tenant and model_deployment_id = :deployment
                """).param("tenant", tenantId).param("deployment", deploymentId)
                .query((rs, row) -> new ModelDeploymentWire(
                        rs.getObject("model_deployment_id", UUID.class), rs.getString("model_code"),
                        rs.getString("provider_code"), rs.getString("display_name"),
                        ModelDeploymentWire.ResidencyPolicyValue.valueOf(rs.getString("residency_policy")),
                        rs.getBoolean("test_only"),
                        rs.getString("endpoint_url"),
                        ModelDeploymentWire.StatusValue.valueOf(rs.getString("status")),
                        ModelDeploymentWire.EvaluationStatusValue.valueOf(rs.getString("evaluation_status")),
                        rs.getBoolean("release_evidence_ready"),
                        rs.getString("api_key_ref") != null, credentialHint(rs.getString("api_key_ref")),
                        ModelDeploymentWire.ConnectionStatusValue.valueOf(rs.getString("connection_status")),
                        rs.getObject("last_connection_tested_at", java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject("last_connection_tested_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("last_connection_latency_ms", Long.class),
                        rs.getString("last_connection_error_code"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ModelDeploymentException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ModelDeploymentException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID deploymentId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", deploymentId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID deploymentId, long version, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + deploymentId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MODEL_DEPLOYMENT', :resource,
                  null, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", deploymentId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MODEL_DEPLOYMENT', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", deploymentId).param("version", version).param("event_type", eventType).update();
    }

    private static ModelDeploymentException invalid(String message) {
        return new ModelDeploymentException("MODEL_DEPLOYMENT_REQUEST_INVALID", 400, message);
    }

    private static String normalizeEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalid("endpoint_url must be an HTTPS address");
            }
            return normalized;
        } catch (IllegalArgumentException invalidUri) {
            throw invalid("endpoint_url must be an HTTPS address");
        }
    }

    private static void rejectRetiredSyntheticConfiguration(String providerCode, String endpointUrl) {
        if ((providerCode != null && "SYNTHETIC".equalsIgnoreCase(providerCode.trim()))
                || isReservedExampleEndpoint(endpointUrl)) {
            throw new ModelDeploymentException("MODEL_DEPLOYMENT_SYNTHETIC_RETIRED", 409,
                    "Reserved development model configurations cannot be used in this system");
        }
    }

    private static boolean isReservedExampleEndpoint(String endpointUrl) {
        if (endpointUrl == null) return false;
        try {
            String host = URI.create(endpointUrl).getHost();
            return host != null && (host.equals("example") || host.endsWith(".example"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String normalizeSecretReference(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!SECRET_REFERENCE.matcher(normalized).matches()) {
            throw invalid("api_key_ref must use env://ENV_NAME or file:///absolute/path");
        }
        return normalized;
    }

    private static String normalizeApiKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() < 8 || normalized.length() > 4096
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw invalid("API Key 需为 8–4096 位且不含空格；请粘贴提供方控制台签发的完整密钥，或留空稍后再填");
        }
        return normalized;
    }

    private static String generatedModelCode() {
        return "model-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String credentialHint(String reference) {
        if (reference == null) return null;
        Optional<String> managed = secretStore.maskedHint(reference);
        if (managed.isPresent()) return managed.get();
        if (reference.startsWith("env://")) return "环境变量 · " + reference.substring("env://".length());
        return "密钥文件 · …/" + reference.substring(reference.lastIndexOf('/') + 1);
    }

    private void deleteOnRollback(String reference) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) secretStore.deleteManaged(reference);
            }
        });
    }

    private void deleteAfterCommit(String reference) {
        if (reference == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                secretStore.deleteManaged(reference);
            }
        });
    }

    static ModelDeploymentException contextDenied() {
        return new ModelDeploymentException("CONTEXT_NOT_PERMITTED", 403, "The requested model context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record DeploymentHead(String status, long rowVersion, String apiKeyRef) {}
    private record DeploymentConfigHead(String status, long rowVersion, String apiKeyRef) {}
    private record PublishHead(String status, String connectionStatus, boolean testOnly, long rowVersion) {}
    private record ConnectionConfig(String modelCode, String endpointUrl, String apiKeyReference,
            String status, long rowVersion) {}
}
