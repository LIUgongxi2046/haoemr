package org.haoemr.archive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationSourceImportWire;
import org.haoemr.contracts.SourcePatientMatchCandidateCsvImportRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateRecordRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateResolveRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SourcePatientMatchCandidateService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SourcePatientMatchCandidateService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    SourcePatientMatchCandidateWire record(
            ClinicalIdentity identity, String idempotencyKey, SourcePatientMatchCandidateRecordRequestWire request) {
        if (request.sourceSystemId() == null || request.birthDate() == null) {
            throw invalid("source_system_id and birth_date are required");
        }
        String sourceIdentifier = requireText(request.sourcePatientIdentifier(), 2, "source_patient_identifier");
        String displayName = requireText(request.displayName(), 2, "display_name");
        String sexCode = requireText(request.sexCode(), 1, "sex_code");
        return transactions.execute(status -> {
            beginCommand(identity, "PATIENT_MATCH_RECORD", idempotencyKey,
                    sha256(request.sourceSystemId() + "|" + sourceIdentifier));
            requireActiveSource(identity.tenantId(), request.sourceSystemId());
            MatchResult match = computeMatch(identity.tenantId(), displayName, sexCode, request.birthDate());
            UUID candidateId = UUID.randomUUID();
            jdbc.sql("""
                    insert into source_patient_match_candidate(
                      tenant_id, candidate_id, source_system_id, source_patient_identifier,
                      display_name, sex_code, birth_date, matched_patient_id, match_score, review_status)
                    values (:tenant, :candidate, :source, :identifier, :name, :sex, :birth,
                      :matched, :score, 'PENDING')
                    """).param("tenant", identity.tenantId()).param("candidate", candidateId)
                    .param("source", request.sourceSystemId()).param("identifier", sourceIdentifier)
                    .param("name", displayName).param("sex", sexCode).param("birth", request.birthDate())
                    .param("matched", match.matchedPatientId())
                    .param("score", match.score()).update();
            appendEvidence(identity, candidateId, "PATIENT_MATCH_CANDIDATE",
                    "PATIENT_MATCH_RECORDED", "PatientMatchRecorded");
            completeCommand(identity, "PATIENT_MATCH_RECORD", idempotencyKey, candidateId);
            return candidate(identity.tenantId(), candidateId);
        });
    }

    HistoricalMigrationSourceImportWire importCsv(
            ClinicalIdentity identity, String idempotencyKey,
            SourcePatientMatchCandidateCsvImportRequestWire request) {
        if (request.sourceSystemId() == null) throw invalid("source_system_id is required");
        String fileName = requireText(request.fileName(), 1, "file_name");
        String csv = request.csvContent();
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".csv") || csv == null
                || csv.length() > 1_048_576) {
            throw invalid("a CSV file up to 1 MiB is required");
        }
        List<CsvRow> rows = parseCsv(csv);
        if (rows.isEmpty() || rows.size() > 1000) throw invalid("CSV must contain 1 to 1000 data rows");
        String contentHash = sha256(csv);
        return transactions.execute(status -> {
            requireActiveSource(identity.tenantId(), request.sourceSystemId());
            UUID existing = jdbc.sql("""
                    select source_import_id from historical_migration_source_import
                    where tenant_id = :tenant and source_system_id = :source and content_hash = :hash
                    """).param("tenant", identity.tenantId()).param("source", request.sourceSystemId())
                    .param("hash", contentHash).query(UUID.class).optional().orElse(null);
            if (existing != null) return sourceImport(identity.tenantId(), existing);
            beginCommand(identity, "HISTORICAL_MIGRATION_CSV_IMPORT", idempotencyKey,
                    sha256(request.sourceSystemId() + "|" + contentHash));
            UUID importId = UUID.randomUUID();
            jdbc.sql("""
                    insert into historical_migration_source_import(
                      tenant_id, source_import_id, source_system_id, file_name, content_hash,
                      row_count, imported_by)
                    values (:tenant, :import, :source, :file_name, :hash, :row_count, :actor)
                    """).param("tenant", identity.tenantId()).param("import", importId)
                    .param("source", request.sourceSystemId()).param("file_name", fileName)
                    .param("hash", contentHash).param("row_count", rows.size()).param("actor", identity.userId()).update();
            for (CsvRow row : rows) {
                if (hasCandidateForSourceIdentifier(
                        identity.tenantId(), request.sourceSystemId(), row.sourcePatientIdentifier())) {
                    throw new SourcePatientMatchCandidateException(
                            "PATIENT_MATCH_SOURCE_IDENTIFIER_EXISTS", 409,
                            "A staged candidate already exists for source_patient_identifier "
                                    + row.sourcePatientIdentifier());
                }
                MatchResult match = computeMatch(identity.tenantId(), row.displayName(), row.sexCode(), row.birthDate());
                UUID candidateId = UUID.randomUUID();
                jdbc.sql("""
                        insert into source_patient_match_candidate(
                          tenant_id, candidate_id, source_system_id, source_import_id,
                          source_patient_identifier, display_name, sex_code, birth_date,
                          matched_patient_id, match_score, review_status)
                        values (:tenant, :candidate, :source, :import, :identifier, :name, :sex, :birth,
                          :matched, :score, 'PENDING')
                        """).param("tenant", identity.tenantId()).param("candidate", candidateId)
                        .param("source", request.sourceSystemId()).param("import", importId)
                        .param("identifier", row.sourcePatientIdentifier()).param("name", row.displayName())
                        .param("sex", row.sexCode()).param("birth", row.birthDate())
                        .param("matched", match.matchedPatientId()).param("score", match.score()).update();
                appendEvidence(identity, candidateId, "PATIENT_MATCH_CANDIDATE",
                        "PATIENT_MATCH_IMPORTED", "PatientMatchImported");
            }
            appendEvidence(identity, importId, "HISTORICAL_MIGRATION_SOURCE_IMPORT",
                    "HISTORICAL_MIGRATION_SOURCE_IMPORTED", "HistoricalMigrationSourceImported");
            completeCommand(identity, "HISTORICAL_MIGRATION_CSV_IMPORT", idempotencyKey, importId);
            return sourceImport(identity.tenantId(), importId);
        });
    }

    SourcePatientMatchCandidateWire resolve(
            ClinicalIdentity identity, String idempotencyKey, UUID candidateId,
            SourcePatientMatchCandidateResolveRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "PATIENT_MATCH_RESOLVE", idempotencyKey,
                    sha256(candidateId + "|" + request.expectedRowVersion()));
            CandidateHead head = lockCandidate(identity.tenantId(), candidateId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw new SourcePatientMatchCandidateException(
                        "PATIENT_MATCH_VERSION_CONFLICT", 409, "The candidate changed; reload before retrying");
            }
            if (!"PENDING".equals(head.reviewStatus())) {
                throw new SourcePatientMatchCandidateException(
                        "PATIENT_MATCH_STATE_INVALID", 409, "Only a pending candidate can be resolved");
            }
            if (request.matchedPatientId() != null) {
                requireActivePatient(identity.tenantId(), request.matchedPatientId());
            }
            jdbc.sql("""
                    update source_patient_match_candidate
                    set review_status = 'RESOLVED', matched_patient_id = :matched,
                      resolved_by = :actor, resolved_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and candidate_id = :candidate and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("candidate", candidateId)
                    .param("matched", request.matchedPatientId()).param("actor", identity.userId())
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, candidateId, "PATIENT_MATCH_CANDIDATE",
                    "PATIENT_MATCH_RESOLVED", "PatientMatchResolved");
            completeCommand(identity, "PATIENT_MATCH_RESOLVE", idempotencyKey, candidateId);
            return candidate(identity.tenantId(), candidateId);
        });
    }

    List<SourcePatientMatchCandidateWire> list(ClinicalIdentity identity, UUID sourceSystemId) {
        return jdbc.sql("""
                select candidate_id from source_patient_match_candidate
                where tenant_id = :tenant and source_system_id = :source
                order by created_at desc, candidate_id desc limit 500
                """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                .query(UUID.class).list().stream()
                .map(id -> candidate(identity.tenantId(), id)).toList();
    }

    List<HistoricalMigrationSourceImportWire> listImports(ClinicalIdentity identity, UUID sourceSystemId) {
        return jdbc.sql("""
                select source_import_id from historical_migration_source_import
                where tenant_id = :tenant and source_system_id = :source
                order by imported_at desc, source_import_id desc limit 200
                """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                .query(UUID.class).list().stream().map(id -> sourceImport(identity.tenantId(), id)).toList();
    }

    private SourcePatientMatchCandidateWire candidate(UUID tenantId, UUID candidateId) {
        return jdbc.sql("""
                select candidate_id, source_system_id, source_patient_identifier, display_name,
                  sex_code, birth_date, matched_patient_id, match_score, review_status,
                  resolved_by, resolved_at, row_version
                from source_patient_match_candidate
                where tenant_id = :tenant and candidate_id = :candidate
                """).param("tenant", tenantId).param("candidate", candidateId)
                .query((rs, row) -> new SourcePatientMatchCandidateWire(
                        rs.getObject("candidate_id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getString("source_patient_identifier"),
                        rs.getString("display_name"),
                        rs.getString("sex_code"),
                        rs.getObject("birth_date", java.time.LocalDate.class),
                        rs.getObject("matched_patient_id", UUID.class),
                        rs.getBigDecimal("match_score").doubleValue(),
                        SourcePatientMatchCandidateWire.ReviewStatusValue.valueOf(rs.getString("review_status")),
                        rs.getObject("resolved_by", UUID.class),
                        rs.getObject("resolved_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("resolved_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(SourcePatientMatchCandidateService::contextDenied);
    }

    private HistoricalMigrationSourceImportWire sourceImport(UUID tenantId, UUID importId) {
        return jdbc.sql("""
                select source_import_id, source_system_id, file_name, content_hash, row_count,
                  imported_by, imported_at
                from historical_migration_source_import
                where tenant_id = :tenant and source_import_id = :import
                """).param("tenant", tenantId).param("import", importId)
                .query((rs, row) -> new HistoricalMigrationSourceImportWire(
                        rs.getObject("source_import_id", UUID.class), rs.getObject("source_system_id", UUID.class),
                        rs.getString("file_name"), rs.getString("content_hash"), rs.getInt("row_count"),
                        rs.getObject("imported_by", UUID.class),
                        rs.getObject("imported_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(SourcePatientMatchCandidateService::contextDenied);
    }

    private MatchResult computeMatch(UUID tenantId, String displayName, String sexCode, java.time.LocalDate birthDate) {
        List<UUID> matches = jdbc.sql("""
                select patient_id from patient
                where tenant_id = :tenant and display_name = :name and sex_code = :sex
                  and birth_date = :birth and status = 'ACTIVE'
                order by patient_id limit 2
                """).param("tenant", tenantId).param("name", displayName).param("sex", sexCode)
                .param("birth", birthDate).query(UUID.class).list();
        if (matches.isEmpty()) {
            return new MatchResult(null, BigDecimal.valueOf(0.0));
        }
        if (matches.size() == 1) {
            return new MatchResult(matches.get(0), BigDecimal.valueOf(1.0));
        }
        return new MatchResult(null, BigDecimal.valueOf(1.0));
    }

    private boolean hasCandidateForSourceIdentifier(UUID tenantId, UUID sourceSystemId, String sourcePatientIdentifier) {
        return jdbc.sql("""
                select exists(
                  select 1 from source_patient_match_candidate
                  where tenant_id = :tenant and source_system_id = :source
                    and source_patient_identifier = :identifier
                )
                """).param("tenant", tenantId).param("source", sourceSystemId)
                .param("identifier", sourcePatientIdentifier).query(Boolean.class).single();
    }

    private void requireActiveSource(UUID tenantId, UUID sourceSystemId) {
        String status = jdbc.sql("""
                select connection_status from source_system_inventory
                where tenant_id = :tenant and source_system_id = :source for update
                """).param("tenant", tenantId).param("source", sourceSystemId)
                .query(String.class).optional().orElseThrow(SourcePatientMatchCandidateService::contextDenied);
        if (!"ACTIVE".equals(status)) {
            throw new SourcePatientMatchCandidateException(
                    "SOURCE_SYSTEM_NOT_ACTIVE", 409, "Only an active source system can produce match candidates");
        }
    }

    private void requireActivePatient(UUID tenantId, UUID patientId) {
        String status = jdbc.sql("""
                select status from patient where tenant_id = :tenant and patient_id = :patient for update
                """).param("tenant", tenantId).param("patient", patientId)
                .query(String.class).optional().orElseThrow(SourcePatientMatchCandidateService::contextDenied);
        if (!"ACTIVE".equals(status)) {
            throw new SourcePatientMatchCandidateException(
                    "PATIENT_INACTIVE", 409, "Only an active patient can resolve a match candidate");
        }
    }

    private CandidateHead lockCandidate(UUID tenantId, UUID candidateId) {
        return jdbc.sql("""
                select review_status, row_version from source_patient_match_candidate
                where tenant_id = :tenant and candidate_id = :candidate for update
                """).param("tenant", tenantId).param("candidate", candidateId)
                .query((rs, row) -> new CandidateHead(rs.getString("review_status"), rs.getLong("row_version")))
                .optional().orElseThrow(SourcePatientMatchCandidateService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new SourcePatientMatchCandidateException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new SourcePatientMatchCandidateException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID candidateId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", candidateId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID resourceId, String resourceType, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("resource_type", resourceType)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", resourceId).param("aggregate_type", resourceType)
                .param("event_type", eventType).update();
    }

    private static List<CsvRow> parseCsv(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length < 2 || !List.of("source_patient_identifier", "display_name", "sex_code", "birth_date")
                .equals(parseCsvLine(lines[0].replaceFirst("^\\uFEFF", "")))) {
            throw invalid("CSV header must be source_patient_identifier,display_name,sex_code,birth_date");
        }
        List<CsvRow> rows = new ArrayList<>();
        HashSet<String> identifiers = new HashSet<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            List<String> values = parseCsvLine(lines[index]);
            if (values.size() != 4) throw invalid("CSV row " + (index + 1) + " must have four columns");
            String identifier = requireText(values.get(0), 2, "source_patient_identifier");
            if (!identifiers.add(identifier)) throw invalid("CSV contains duplicate source_patient_identifier");
            String name = requireText(values.get(1), 2, "display_name");
            String sex = requireText(values.get(2), 1, "sex_code");
            LocalDate birth;
            try { birth = LocalDate.parse(values.get(3).trim()); }
            catch (RuntimeException invalidDate) { throw invalid("CSV birth_date must be ISO-8601 date"); }
            rows.add(new CsvRow(identifier, name, sex, birth));
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') { value.append('"'); index++; }
                else quoted = !quoted;
            } else if (current == ',' && !quoted) { values.add(value.toString()); value.setLength(0); }
            else value.append(current);
        }
        if (quoted) throw invalid("CSV contains an unterminated quoted value");
        values.add(value.toString());
        return values;
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static SourcePatientMatchCandidateException invalid(String message) {
        return new SourcePatientMatchCandidateException("PATIENT_MATCH_REQUEST_INVALID", 400, message);
    }

    static SourcePatientMatchCandidateException contextDenied() {
        return new SourcePatientMatchCandidateException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested patient match context is not permitted");
    }

    private record MatchResult(UUID matchedPatientId, BigDecimal score) {}
    private record CandidateHead(String reviewStatus, long rowVersion) {}
    private record CsvRow(String sourcePatientIdentifier, String displayName, String sexCode, LocalDate birthDate) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
