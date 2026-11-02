alter table medical_ai_external_processing_approval
  add column approval_kind varchar(32) not null default 'CLINICAL'
    check (approval_kind in ('CLINICAL', 'SYNTHETIC_DEMO')),
  alter column pia_reference drop not null,
  alter column processor_agreement_reference drop not null,
  alter column endpoint_region drop not null,
  alter column retention_days drop not null;

alter table medical_ai_external_processing_approval
  add constraint medical_ai_processing_approval_kind_fields check (
    (approval_kind = 'CLINICAL' and pia_reference is not null
      and processor_agreement_reference is not null and endpoint_region is not null
      and retention_days is not null)
    or (approval_kind = 'SYNTHETIC_DEMO' and pia_reference is null
      and processor_agreement_reference is null and endpoint_region is null
      and retention_days is null
      and not ('ATTACHMENTS' = any(allowed_context_scopes))
      and tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
      and expires_at <= approved_at + interval '7 days')
  );
