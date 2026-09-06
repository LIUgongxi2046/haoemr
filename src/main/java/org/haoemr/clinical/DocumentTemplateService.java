package org.haoemr.clinical;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class DocumentTemplateService {
    private static final List<String> ADMIN_ROLES = List.of("SYSTEM_ADMIN", "CLINICAL_ADMIN");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    DocumentTemplateService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<DocumentTemplateWire> list(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select template.template_id, template.template_code, template.display_name,
                  template.document_type_code, template.organization_id, template.facility_id,
                  template.department_id, template.lifecycle_status, template.row_version as template_row_version,
                  version.template_version_id, version.version_no, version.status as version_status,
                  version.section_schema::text, version.required_fields, version.display_rules::text,
                  version.effective_from, version.effective_until, version.created_by, version.approved_by,
                  version.published_at, version.row_version as version_row_version,
                  version.created_at, version.updated_at
                from clinical_document_template template
                join clinical_document_template_version version
                  on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                where template.tenant_id = :tenant
                order by template.display_name, template.template_id, version.version_no desc
                """).param("tenant", identity.tenantId()).query((rs, row) -> wire(rs)).list();
    }

    DocumentTemplateWire create(ClinicalIdentity identity, String key, DocumentTemplateCreateRequest request) {
        requireAdministrator(identity); validate(request == null ? null : request.sectionSchema(),
                request == null ? null : request.requiredFields());
        if (request == null || blank(request.templateCode()) || blank(request.displayName())
                || blank(request.documentTypeCode())) throw invalid("Template code, name and document type are required");
        return transactions.execute(ignored -> {
            UUID templateId = request.templateId() == null ? UUID.randomUUID() : request.templateId();
            UUID versionId = UUID.randomUUID();
            begin(identity, "DOCUMENT_TEMPLATE_CREATE", key, sha256(request.toString()));
            try {
                jdbc.sql("""
                        insert into clinical_document_template(
                          tenant_id, template_id, template_code, display_name, document_type_code,
                          organization_id, facility_id, department_id, created_by)
                        values (:tenant, :template, :code, :name, :type, :organization, :facility,
                          :department, :creator)
                        """).param("tenant", identity.tenantId()).param("template", templateId)
                        .param("code", request.templateCode().trim()).param("name", request.displayName().trim())
                        .param("type", request.documentTypeCode().trim()).param("organization", request.organizationId())
                        .param("facility", request.facilityId()).param("department", request.departmentId())
                        .param("creator", identity.userId()).update();
                insertVersion(identity, templateId, versionId, 1, request.sectionSchema(),
                        request.requiredFields(), request.displayRules());
            } catch (DataIntegrityViolationException conflict) {
                throw conflict("Template code or active scope already exists");
            }
            evidence(identity, "DOCUMENT_TEMPLATE_CREATED", templateId, 1);
            complete(identity, "DOCUMENT_TEMPLATE_CREATE", key, versionId);
            return find(identity.tenantId(), templateId, versionId);
        });
    }

    DocumentTemplateWire createVersion(ClinicalIdentity identity, String key, UUID templateId,
            DocumentTemplateVersionCreateRequest request) {
        requireAdministrator(identity); validate(request == null ? null : request.sectionSchema(),
                request == null ? null : request.requiredFields());
        if (request == null || request.expectedTemplateRowVersion() < 1) throw invalid("Expected template row version is required");
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_TEMPLATE_VERSION_CREATE", key, sha256(templateId + "|" + request));
            int next = jdbc.sql("""
                    select coalesce(max(version.version_no), 0) + 1
                    from clinical_document_template template
                    left join clinical_document_template_version version
                      on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                    where template.tenant_id = :tenant and template.template_id = :template
                      and template.lifecycle_status = 'ACTIVE' and template.row_version = :expected
                    group by template.template_id
                    """).param("tenant", identity.tenantId()).param("template", templateId)
                    .param("expected", request.expectedTemplateRowVersion()).query(Integer.class).optional()
                    .orElseThrow(() -> conflict("Template changed or is inactive"));
            UUID versionId = UUID.randomUUID();
            insertVersion(identity, templateId, versionId, next, request.sectionSchema(),
                    request.requiredFields(), request.displayRules());
            int updated = jdbc.sql("""
                    update clinical_document_template set row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and template_id = :template and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("template", templateId)
                    .param("expected", request.expectedTemplateRowVersion()).update();
            if (updated != 1) throw conflict("Template changed while creating a version");
            evidence(identity, "DOCUMENT_TEMPLATE_VERSION_CREATED", templateId, next);
            complete(identity, "DOCUMENT_TEMPLATE_VERSION_CREATE", key, versionId);
            return find(identity.tenantId(), templateId, versionId);
        });
    }

    DocumentTemplateWire publish(ClinicalIdentity identity, String key, UUID templateId, UUID versionId,
            DocumentTemplateVersionPublishRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedVersionRowVersion() < 1 || request.effectiveFrom() == null)
            throw invalid("Expected version row and effective-from are required");
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_TEMPLATE_VERSION_PUBLISH", key, sha256(templateId + "|" + versionId + "|" + request));
            VersionOwner owner = jdbc.sql("""
                    select version.created_by, version.status
                    from clinical_document_template_version version
                    join clinical_document_template template on template.tenant_id = version.tenant_id
                      and template.template_id = version.template_id
                    where version.tenant_id = :tenant and version.template_id = :template
                      and version.template_version_id = :version and template.lifecycle_status = 'ACTIVE'
                    for update of version, template
                    """).param("tenant", identity.tenantId()).param("template", templateId).param("version", versionId)
                    .query((rs, row) -> new VersionOwner(rs.getObject("created_by", UUID.class), rs.getString("status")))
                    .optional().orElseThrow(() -> conflict("Template version is not publishable"));
            if (!"DRAFT".equals(owner.status()) || owner.createdBy().equals(identity.userId()))
                throw conflict("An independent administrator must publish a draft version");
            jdbc.sql("""
                    update clinical_document_template_version
                    set status = 'RETIRED', effective_until = :effective, row_version = row_version + 1,
                      updated_at = now()
                    where tenant_id = :tenant and template_id = :template and status = 'PUBLISHED'
                    """).param("tenant", identity.tenantId()).param("template", templateId)
                    .param("effective", OffsetDateTime.ofInstant(request.effectiveFrom(), ZoneOffset.UTC)).update();
            int updated = jdbc.sql("""
                    update clinical_document_template_version
                    set status = 'PUBLISHED', approved_by = :approver, published_at = now(),
                      effective_from = :effective, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and template_id = :template
                      and template_version_id = :version and status = 'DRAFT' and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("template", templateId).param("version", versionId)
                    .param("approver", identity.userId())
                    .param("effective", OffsetDateTime.ofInstant(request.effectiveFrom(), ZoneOffset.UTC))
                    .param("expected", request.expectedVersionRowVersion()).update();
            if (updated != 1) throw conflict("Template version changed before publication");
            evidence(identity, "DOCUMENT_TEMPLATE_VERSION_PUBLISHED", templateId,
                    jdbc.sql("select version_no from clinical_document_template_version where tenant_id=:tenant and template_version_id=:version")
                            .param("tenant", identity.tenantId()).param("version", versionId).query(Integer.class).single());
            complete(identity, "DOCUMENT_TEMPLATE_VERSION_PUBLISH", key, versionId);
            return find(identity.tenantId(), templateId, versionId);
        });
    }

    DocumentTemplateWire deactivate(ClinicalIdentity identity, String key, UUID templateId,
            DocumentTemplateDeactivateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedTemplateRowVersion() < 1 || blank(request.reason()))
            throw invalid("Expected row version and reason are required");
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_TEMPLATE_DEACTIVATE", key, sha256(templateId + "|" + request));
            jdbc.sql("""
                    update clinical_document_template_version set status = 'RETIRED', effective_until = now(),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and template_id = :template and status = 'PUBLISHED'
                    """).param("tenant", identity.tenantId()).param("template", templateId).update();
            int updated = jdbc.sql("""
                    update clinical_document_template set lifecycle_status = 'INACTIVE',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and template_id = :template
                      and lifecycle_status = 'ACTIVE' and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("template", templateId)
                    .param("expected", request.expectedTemplateRowVersion()).update();
            if (updated != 1) throw conflict("Template changed or is already inactive");
            evidence(identity, "DOCUMENT_TEMPLATE_DEACTIVATED", templateId, request.expectedTemplateRowVersion() + 1);
            complete(identity, "DOCUMENT_TEMPLATE_DEACTIVATE", key, templateId);
            return jdbc.sql("""
                    select template.template_id, template.template_code, template.display_name,
                      template.document_type_code, template.organization_id, template.facility_id,
                      template.department_id, template.lifecycle_status, template.row_version as template_row_version,
                      version.template_version_id, version.version_no, version.status as version_status,
                      version.section_schema::text, version.required_fields, version.display_rules::text,
                      version.effective_from, version.effective_until, version.created_by, version.approved_by,
                      version.published_at, version.row_version as version_row_version,
                      version.created_at, version.updated_at
                    from clinical_document_template template join clinical_document_template_version version
                      on version.tenant_id=template.tenant_id and version.template_id=template.template_id
                    where template.tenant_id=:tenant and template.template_id=:template
                    order by version.version_no desc limit 1
                    """).param("tenant", identity.tenantId()).param("template", templateId)
                    .query((rs, row) -> wire(rs)).single();
        });
    }

    private void insertVersion(ClinicalIdentity identity, UUID templateId, UUID versionId, int versionNo,
            Map<String, Object> schema, List<String> required, Map<String, Object> displayRules) {
        Map<String, Object> effectiveDisplayRules = new LinkedHashMap<>(displayRules == null ? Map.of() : displayRules);
        LinkedHashSet<String> effectiveRequired = new LinkedHashSet<>(required == null ? List.of() : required);
        FormConfigurationBinding form = resolveFormBinding(identity.tenantId(), schema, effectiveDisplayRules);
        if (form != null) {
            effectiveRequired.addAll(form.requiredFields());
            effectiveDisplayRules.put("form_config_key", form.configKey());
            effectiveDisplayRules.put("form_config_id", form.configId().toString());
            effectiveDisplayRules.put("form_config_row_version", form.rowVersion());
        }
        jdbc.sql("""
                insert into clinical_document_template_version(
                  tenant_id, template_id, template_version_id, version_no, status,
                  section_schema, required_fields, display_rules, created_by)
                values (:tenant, :template, :version, :version_no, 'DRAFT', cast(:schema as jsonb),
                  cast(:required as text[]), cast(:display as jsonb), :creator)
                """).param("tenant", identity.tenantId()).param("template", templateId).param("version", versionId)
                .param("version_no", versionNo).param("schema", json(schema))
                .param("required", textArray(List.copyOf(effectiveRequired))).param("display", json(effectiveDisplayRules))
                .param("creator", identity.userId()).update();
    }

    /**
     * A published form is copied into the immutable document-template version as a binding, not
     * read dynamically at note-signing time. This prevents later form edits from silently
     * changing historical documentation requirements.
     */
    private FormConfigurationBinding resolveFormBinding(
            UUID tenantId, Map<String, Object> schema, Map<String, Object> displayRules) {
        Object configuredKey = displayRules.get("form_config_key");
        if (configuredKey == null || String.valueOf(configuredKey).isBlank()) return null;
        String key = String.valueOf(configuredKey).trim();
        if (key.length() > 128) throw invalid("Form configuration key is too long");
        String runtimeKey = "runtime-" + key;
        FormConfigurationBinding binding = jdbc.sql("""
                select config_id, row_version, payload::text from config_item
                where tenant_id = :tenant and config_type = 'FORM_TEMPLATE'
                  and config_key = :runtime_key and status = 'ACTIVE'
                """).param("tenant", tenantId).param("runtime_key", runtimeKey)
                .query((rs, row) -> new FormConfigurationBinding(
                        rs.getObject("config_id", UUID.class), key, rs.getLong("row_version"),
                        requiredFormFields(map(rs.getString("payload")))))
                .optional().orElseThrow(() -> invalid("The selected form configuration is not published"));
        Object rawProperties = schema == null ? null : schema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties) || !properties.keySet().containsAll(binding.requiredFields())) {
            throw invalid("Every required field from the selected form configuration must exist in the document template schema");
        }
        return binding;
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredFormFields(Map<String, Object> payload) {
        Object rawFields = payload.get("fields");
        if (!(rawFields instanceof List<?> fields)) return List.of();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (Object raw : fields) {
            if (!(raw instanceof Map<?, ?> field) || !Boolean.TRUE.equals(field.get("required"))) continue;
            Object id = field.get("id");
            if (id != null && !String.valueOf(id).isBlank()) required.add(String.valueOf(id).trim());
        }
        return List.copyOf(required);
    }

    private DocumentTemplateWire find(UUID tenant, UUID templateId, UUID versionId) {
        return jdbc.sql("""
                select template.template_id, template.template_code, template.display_name,
                  template.document_type_code, template.organization_id, template.facility_id,
                  template.department_id, template.lifecycle_status, template.row_version as template_row_version,
                  version.template_version_id, version.version_no, version.status as version_status,
                  version.section_schema::text, version.required_fields, version.display_rules::text,
                  version.effective_from, version.effective_until, version.created_by, version.approved_by,
                  version.published_at, version.row_version as version_row_version,
                  version.created_at, version.updated_at
                from clinical_document_template template join clinical_document_template_version version
                  on version.tenant_id=template.tenant_id and version.template_id=template.template_id
                where template.tenant_id=:tenant and template.template_id=:template
                  and version.template_version_id=:version
                """).param("tenant", tenant).param("template", templateId).param("version", versionId)
                .query((rs, row) -> wire(rs)).single();
    }

    private DocumentTemplateWire wire(ResultSet rs) throws SQLException {
        return new DocumentTemplateWire(rs.getObject("template_id", UUID.class), rs.getString("template_code"),
                rs.getString("display_name"), rs.getString("document_type_code"),
                rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                rs.getObject("department_id", UUID.class), rs.getString("lifecycle_status"),
                rs.getLong("template_row_version"), rs.getObject("template_version_id", UUID.class),
                rs.getInt("version_no"), rs.getString("version_status"), map(rs.getString("section_schema")),
                Arrays.asList((String[]) rs.getArray("required_fields").getArray()),
                map(rs.getString("display_rules")), instant(rs.getObject("effective_from", OffsetDateTime.class)),
                instant(rs.getObject("effective_until", OffsetDateTime.class)),
                rs.getObject("created_by", UUID.class), rs.getObject("approved_by", UUID.class),
                instant(rs.getObject("published_at", OffsetDateTime.class)), rs.getLong("version_row_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private void validate(Map<String, Object> schema, List<String> required) {
        if (schema == null || !"object".equals(schema.get("type"))) throw invalid("Section schema must be a JSON object schema");
        List<String> fields = required == null ? List.of() : required;
        if (new HashSet<>(fields).size() != fields.size() || fields.stream().anyMatch(DocumentTemplateService::blank))
            throw invalid("Required fields must be unique nonblank section keys");
        Object properties = schema.get("properties");
        if (!fields.isEmpty() && (!(properties instanceof Map<?, ?> values) || !values.keySet().containsAll(fields)))
            throw invalid("Every required field must exist in section schema properties");
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        long count = jdbc.sql("""
                select count(*) from role_assignment where tenant_id=:tenant and user_id=:user
                  and role_assignment_id=any(cast(:roles as uuid[])) and role_code=any(cast(:codes as text[]))
                  and status='ACTIVE' and valid_from<=now() and (valid_until is null or valid_until>now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", uuidArray(identity.roleAssignmentIds())).param("codes", textArray(ADMIN_ROLES))
                .query(Long.class).single();
        if (count < 1) throw new DocumentTemplateException("DOCUMENT_TEMPLATE_ACCESS_DENIED", 403,
                "Active clinical or system administrator role is required");
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (blank(key) || key.length() > 128) throw invalid("A valid Idempotency-Key is required");
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id, command_scope, idempotency_key,
                  request_hash, state, trace_id, expires_at)
                values (:tenant,:scope,:key,:hash,'IN_PROGRESS',:trace,now()+interval '24 hours')
                on conflict (tenant_id,command_scope,idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw conflict("This template command was already submitted");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resource) {
        jdbc.sql("""
                update idempotency_record set state='SUCCEEDED', response_status=200,
                  response_ref=jsonb_build_object('resource_type','DOCUMENT_TEMPLATE','resource_id',:resource)
                where tenant_id=:tenant and command_scope=:scope and idempotency_key=:key
                """).param("resource", resource).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void evidence(ClinicalIdentity identity, String action, UUID templateId, long version) {
        jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id=:tenant order by occurred_at desc,audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId()+"|"+audit+"|"+action+"|"+templateId+"|"+trace+"|"+previous);
        jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,
                  resource_type,resource_id,trace_id,previous_hash,event_hash,details)
                values (:tenant,:audit,now(),:actor,:action,'DOCUMENT_TEMPLATE',:resource,:trace,
                  :previous,:hash,jsonb_build_object('version',:version))
                """).param("tenant",identity.tenantId()).param("audit",audit).param("actor",identity.userId())
                .param("action",action).param("resource",templateId).param("trace",trace)
                .param("previous",previous).param("hash",hash).param("version",version).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,
                  event_type,schema_version,payload) values (:tenant,:event,'DOCUMENT_TEMPLATE',:resource,
                  :version,:event_type,1,jsonb_build_object('template_id',:resource,'version',:version))
                """).param("tenant",identity.tenantId()).param("event",UUID.randomUUID())
                .param("resource",templateId).param("version",version).param("event_type",action).update();
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception failure) { throw invalid("Template JSON is invalid"); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(String value) {
        try { return objectMapper.convertValue(objectMapper.readTree(value), Map.class); }
        catch (Exception failure) { throw new IllegalStateException("Stored template JSON is invalid", failure); }
    }
    private static String uuidArray(List<UUID> values) { return "{"+values.stream().map(UUID::toString).reduce((a,b)->a+","+b).orElse("")+"}"; }
    private static String textArray(List<String> values) { return "{"+String.join(",",values)+"}"; }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static DocumentTemplateException invalid(String message) { return new DocumentTemplateException("DOCUMENT_TEMPLATE_REQUEST_INVALID",400,message); }
    private static DocumentTemplateException conflict(String message) { return new DocumentTemplateException("DOCUMENT_TEMPLATE_CONFLICT",409,message); }

    record DocumentTemplateCreateRequest(
            @JsonProperty("template_id") UUID templateId,
            @JsonProperty("template_code") String templateCode,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("document_type_code") String documentTypeCode,
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("facility_id") UUID facilityId,
            @JsonProperty("department_id") UUID departmentId,
            @JsonProperty("section_schema") Map<String,Object> sectionSchema,
            @JsonProperty("required_fields") List<String> requiredFields,
            @JsonProperty("display_rules") Map<String,Object> displayRules) {}
    record DocumentTemplateVersionCreateRequest(
            @JsonProperty("expected_template_row_version") long expectedTemplateRowVersion,
            @JsonProperty("section_schema") Map<String,Object> sectionSchema,
            @JsonProperty("required_fields") List<String> requiredFields,
            @JsonProperty("display_rules") Map<String,Object> displayRules) {}
    record DocumentTemplateVersionPublishRequest(
            @JsonProperty("expected_version_row_version") long expectedVersionRowVersion,
            @JsonProperty("effective_from") Instant effectiveFrom) {}
    record DocumentTemplateDeactivateRequest(
            @JsonProperty("expected_template_row_version") long expectedTemplateRowVersion,
            String reason) {}
    record DocumentTemplateWire(UUID templateId, String templateCode, String displayName,
            String documentTypeCode, UUID organizationId, UUID facilityId, UUID departmentId,
            String lifecycleStatus, long templateRowVersion, UUID templateVersionId, int versionNo,
            String versionStatus, Map<String,Object> sectionSchema, List<String> requiredFields,
            Map<String,Object> displayRules, Instant effectiveFrom, Instant effectiveUntil,
            UUID createdBy, UUID approvedBy, Instant publishedAt, long versionRowVersion,
            Instant createdAt, Instant updatedAt) {}
    private record VersionOwner(UUID createdBy, String status) {}
    private record FormConfigurationBinding(UUID configId, String configKey, long rowVersion, List<String> requiredFields) {}
}
