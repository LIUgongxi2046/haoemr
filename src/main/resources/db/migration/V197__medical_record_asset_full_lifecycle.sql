alter table medical_record_asset
  add column display_name varchar(256) not null default '病案资产',
  add column media_type varchar(96) not null default 'application/octet-stream',
  add column page_count integer not null default 1,
  add column source_system varchar(128) not null default 'haoemr',
  add column custody_location varchar(128),
  add column integrity_status varchar(16) not null default 'PENDING',
  add column cda_status varchar(24) not null default 'NOT_APPLICABLE',
  add column scan_status varchar(24) not null default 'NOT_APPLICABLE',
  add column preservation_status varchar(24) not null default 'NOT_SCHEDULED',
  add column retention_years integer not null default 15,
  add column last_verified_at timestamptz,
  add column retired_by uuid,
  add column retired_at timestamptz,
  add column retirement_reason varchar(1000);

update medical_record_asset set custody_location = location where custody_location is null;
alter table medical_record_asset alter column custody_location set not null;

alter table medical_record_asset drop constraint medical_record_asset_status_check;
alter table medical_record_asset add constraint medical_record_asset_status_check
  check (status in ('ARCHIVED', 'BORROWED', 'RETIRED'));
alter table medical_record_asset add constraint medical_record_asset_display_name_check
  check (length(trim(display_name)) between 2 and 256);
alter table medical_record_asset add constraint medical_record_asset_media_type_check
  check (length(trim(media_type)) between 3 and 96);
alter table medical_record_asset add constraint medical_record_asset_page_count_check
  check (page_count between 1 and 100000);
alter table medical_record_asset add constraint medical_record_asset_source_system_check
  check (length(trim(source_system)) between 2 and 128);
alter table medical_record_asset add constraint medical_record_asset_custody_location_check
  check (length(trim(custody_location)) between 2 and 128);
alter table medical_record_asset add constraint medical_record_asset_integrity_status_check
  check (integrity_status in ('PENDING', 'VERIFIED', 'FAILED'));
alter table medical_record_asset add constraint medical_record_asset_cda_status_check
  check (cda_status in ('NOT_APPLICABLE', 'PENDING', 'VERIFIED', 'FAILED'));
alter table medical_record_asset add constraint medical_record_asset_scan_status_check
  check (scan_status in ('NOT_APPLICABLE', 'CAPTURED', 'OCR_REVIEWED', 'INDEXED'));
alter table medical_record_asset add constraint medical_record_asset_preservation_status_check
  check (preservation_status in ('NOT_SCHEDULED', 'SCHEDULED', 'SEALED', 'VERIFIED'));
alter table medical_record_asset add constraint medical_record_asset_retention_years_check
  check (retention_years between 1 and 100);
alter table medical_record_asset add constraint medical_record_asset_retirement_check
  check ((status = 'RETIRED') =
    (retired_by is not null and retired_at is not null and length(trim(retirement_reason)) >= 4));
alter table medical_record_asset add constraint medical_record_asset_retired_by_fk
  foreign key (tenant_id, retired_by) references app_user(tenant_id, user_id);

create table medical_record_asset_integrity_event (
  tenant_id uuid not null,
  integrity_event_id uuid not null,
  medical_record_asset_id uuid not null,
  expected_hash char(64) not null check (expected_hash ~ '^[0-9a-f]{64}$'),
  observed_hash char(64) not null check (observed_hash ~ '^[0-9a-f]{64}$'),
  result varchar(16) not null check (result in ('VERIFIED', 'FAILED')),
  verified_by uuid not null,
  verified_at timestamptz not null default now(),
  primary key (tenant_id, integrity_event_id),
  foreign key (tenant_id, medical_record_asset_id)
    references medical_record_asset(tenant_id, medical_record_asset_id),
  foreign key (tenant_id, verified_by) references app_user(tenant_id, user_id)
);

create index medical_record_asset_integrity_event_idx
  on medical_record_asset_integrity_event(tenant_id, medical_record_asset_id, verified_at desc);

create function prevent_medical_record_asset_integrity_event_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'medical record asset integrity evidence is immutable' using errcode = '23514';
end $$;

create trigger medical_record_asset_integrity_event_immutable
  before update or delete on medical_record_asset_integrity_event
  for each row execute function prevent_medical_record_asset_integrity_event_mutation();
