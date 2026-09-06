CREATE TABLE medical_agent_reminder_adoption (
    tenant_id uuid NOT NULL,
    reminder_id uuid NOT NULL,
    source_run_id uuid NOT NULL,
    source_payload_hash varchar(64) NOT NULL,
    confirmed_by uuid NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, reminder_id),
    FOREIGN KEY (tenant_id, reminder_id) REFERENCES clinical_reminder(tenant_id, reminder_id),
    FOREIGN KEY (tenant_id, source_run_id) REFERENCES medical_agent_run(tenant_id, run_id),
    FOREIGN KEY (tenant_id, confirmed_by) REFERENCES app_user(tenant_id, user_id)
);
CREATE TRIGGER medical_agent_reminder_adoption_immutable
    BEFORE UPDATE OR DELETE ON medical_agent_reminder_adoption
    FOR EACH ROW EXECUTE FUNCTION prevent_medical_agent_adoption_mutation();
