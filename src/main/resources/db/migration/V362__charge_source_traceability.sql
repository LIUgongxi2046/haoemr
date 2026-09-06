alter table charge_item
  add column source_type varchar(32) not null default 'MANUAL'
    check (source_type in ('MANUAL', 'ORDER_EXECUTION', 'PHARMACY_DISPENSING')),
  add column source_id uuid,
  add column price_version_id uuid;

alter table charge_item
  add constraint charge_item_price_version_fk
    foreign key (tenant_id, price_version_id)
    references price_catalog_version(tenant_id, price_version_id);

create unique index charge_item_source_once_idx
  on charge_item (tenant_id, source_type, source_id)
  where source_id is not null;

create index charge_item_source_lookup_idx
  on charge_item (tenant_id, source_type, source_id);

create table billing_charge_exception (
  tenant_id uuid not null,
  charge_exception_id uuid not null,
  source_type varchar(32) not null check (source_type in ('ORDER_EXECUTION', 'PHARMACY_DISPENSING')),
  source_id uuid not null,
  patient_id uuid not null,
  encounter_id uuid not null,
  facility_id uuid not null,
  item_code varchar(128) not null,
  exception_code varchar(64) not null,
  detail varchar(1000) not null,
  detected_at timestamptz not null default now(),
  primary key (tenant_id, charge_exception_id),
  unique (tenant_id, source_type, source_id),
  foreign key (tenant_id, patient_id) references patient(tenant_id, patient_id),
  foreign key (tenant_id, encounter_id) references encounter(tenant_id, encounter_id),
  foreign key (tenant_id, facility_id) references facility(tenant_id, facility_id)
);

create index billing_charge_exception_encounter_idx
  on billing_charge_exception (tenant_id, encounter_id, detected_at desc);

alter table charge_item disable trigger charge_item_immutable;
drop trigger charge_item_immutable on charge_item;
create trigger charge_item_immutable
  before update of item_code, quantity, unit_price, amount, unit, charged_at, charged_by,
    source_type, source_id, price_version_id on charge_item
  for each row execute function prevent_charge_item_price_mutation();
