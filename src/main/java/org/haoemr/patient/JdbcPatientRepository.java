package org.haoemr.patient;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPatientRepository implements PatientRepository {

    private final JdbcClient jdbcClient;

    public JdbcPatientRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<PatientRecord> findById(UUID tenantId, UUID patientId) {
        return jdbcClient.sql("""
                select tenant_id, patient_id, display_name, sex_code, birth_date, row_version
                from patient
                where tenant_id = :tenant_id and patient_id = :patient_id
                """)
                .param("tenant_id", tenantId)
                .param("patient_id", patientId)
                .query((rs, rowNum) -> new PatientRecord(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("sex_code"),
                        rs.getObject("birth_date", java.time.LocalDate.class),
                        rs.getLong("row_version")))
                .optional();
    }
}
