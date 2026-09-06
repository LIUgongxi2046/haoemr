package org.haoemr.research;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class ResearchCohortCriteriaEvaluator {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "age_gte", "age_lte", "sex", "diagnosis_code", "encounter_since");

    private final JdbcClient jdbc;

    ResearchCohortCriteriaEvaluator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void validate(String criteria) {
        parse(criteria);
    }

    boolean matches(UUID tenantId, UUID patientId, String criteria) {
        if (criteria == null || criteria.isBlank()) return false;
        Map<String, String> clauses = parse(criteria);
        PatientFacts patient = jdbc.sql("""
                select sex_code, birth_date from patient
                where tenant_id = :tenant and patient_id = :patient and status = 'ACTIVE'
                """).param("tenant", tenantId).param("patient", patientId)
                .query((rs, row) -> new PatientFacts(
                        rs.getString("sex_code"), rs.getObject("birth_date", LocalDate.class)))
                .optional().orElseThrow(ResearchCohortMemberService::contextDenied);
        int age = Period.between(patient.birthDate(), LocalDate.now(ZoneOffset.UTC)).getYears();
        if (clauses.containsKey("age_gte") && age < integer(clauses.get("age_gte"), "age_gte")) return false;
        if (clauses.containsKey("age_lte") && age > integer(clauses.get("age_lte"), "age_lte")) return false;
        if (clauses.containsKey("sex") && !patient.sexCode().equalsIgnoreCase(clauses.get("sex"))) return false;
        if (clauses.containsKey("encounter_since") && !hasEncounterSince(
                tenantId, patientId, date(clauses.get("encounter_since"), "encounter_since"))) return false;
        return !clauses.containsKey("diagnosis_code") || hasDiagnosis(
                tenantId, patientId, clauses.get("diagnosis_code"));
    }

    private boolean hasEncounterSince(UUID tenantId, UUID patientId, LocalDate since) {
        return jdbc.sql("""
                select exists(select 1 from encounter
                  where tenant_id = :tenant and patient_id = :patient and status <> 'CANCELLED'
                    and started_at >= :since)
                """).param("tenant", tenantId).param("patient", patientId)
                .param("since", since.atStartOfDay().atOffset(ZoneOffset.UTC))
                .query(Boolean.class).single();
    }

    private boolean hasDiagnosis(UUID tenantId, UUID patientId, String code) {
        return jdbc.sql("""
                select exists(select 1 from clinical_diagnosis d
                  join clinical_diagnosis_version v
                    on v.tenant_id = d.tenant_id and v.diagnosis_version_id = d.current_version_id
                  where d.tenant_id = :tenant and d.patient_id = :patient
                    and d.lifecycle_status = 'ACTIVE' and v.code = :code)
                """).param("tenant", tenantId).param("patient", patientId).param("code", code)
                .query(Boolean.class).single();
    }

    private static Map<String, String> parse(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            throw invalid("criteria must use the allowlisted key=value syntax");
        }
        List<String> parts = Arrays.stream(criteria.split(";"))
                .map(String::trim).filter(part -> !part.isBlank()).toList();
        if (parts.isEmpty() || parts.size() > 8) throw invalid("criteria must contain 1 to 8 clauses");
        try {
            Map<String, String> result = parts.stream().map(part -> part.split("=", 2))
                    .peek(pair -> {
                        if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) throw invalid("invalid criterion");
                    }).collect(Collectors.toUnmodifiableMap(
                            pair -> pair[0].trim().toLowerCase(Locale.ROOT), pair -> pair[1].trim()));
            if (!ALLOWED_KEYS.containsAll(result.keySet())) {
                throw invalid("unsupported criterion; allowed: " + String.join(",", ALLOWED_KEYS));
            }
            if (result.containsKey("age_gte")) integer(result.get("age_gte"), "age_gte");
            if (result.containsKey("age_lte")) integer(result.get("age_lte"), "age_lte");
            if (result.containsKey("encounter_since")) date(result.get("encounter_since"), "encounter_since");
            if (result.containsKey("sex") && !Set.of("M", "F", "U").contains(result.get("sex").toUpperCase(Locale.ROOT)))
                throw invalid("sex must be M, F or U");
            return result;
        } catch (ResearchCriteriaException criteriaFailure) {
            throw criteriaFailure;
        } catch (IllegalStateException duplicate) {
            throw invalid("duplicate criterion key");
        }
    }

    private static int integer(String value, String key) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 130) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalid(key + " must be an integer between 0 and 130");
        }
    }

    private static LocalDate date(String value, String key) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException invalid) {
            throw invalid(key + " must use ISO date YYYY-MM-DD");
        }
    }

    private static ResearchCriteriaException invalid(String message) {
        return new ResearchCriteriaException(message);
    }

    private record PatientFacts(String sexCode, LocalDate birthDate) {}

    static final class ResearchCriteriaException extends IllegalArgumentException {
        ResearchCriteriaException(String message) { super(message); }
    }
}
