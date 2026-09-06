create table settlement_receipt (
  tenant_id uuid not null,
  settlement_receipt_id uuid not null,
  patient_id uuid not null,
  encounter_id uuid not null,
  facility_id uuid not null,
  amount numeric(18,2) not null check (amount > 0),
  payment_method_code varchar(64) not null check (length(trim(payment_method_code)) > 0),
  channel_code varchar(64) not null check (length(trim(channel_code)) > 0),
  external_receipt_no varchar(128) not null check (length(trim(external_receipt_no)) > 0),
  received_at timestamptz not null,
  received_by uuid not null,
  created_at timestamptz not null default now(),
  primary key (tenant_id, settlement_receipt_id),
  unique (tenant_id, channel_code, external_receipt_no),
  foreign key (tenant_id, patient_id) references patient(tenant_id, patient_id),
  foreign key (tenant_id, encounter_id) references encounter(tenant_id, encounter_id),
  foreign key (tenant_id, facility_id) references facility(tenant_id, facility_id),
  foreign key (tenant_id, received_by) references app_user(tenant_id, user_id)
);

create index settlement_receipt_encounter_idx
  on settlement_receipt (tenant_id, encounter_id, received_at desc, settlement_receipt_id desc);
