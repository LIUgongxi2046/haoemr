#!/usr/bin/env bash
set -euo pipefail

pg_bin="${HAOEMR_PG_BIN:-/usr/local/opt/postgresql@18/bin}"
pg_socket="${HAOEMR_PG_SOCKET:-/private/tmp}"
pg_port="${HAOEMR_PG_PORT:-55432}"
source_database="${HAOEMR_PG_DATABASE:-haoemr_dev}"
restore_database="haoemr_restore_verify"
backup_file="/private/tmp/haoemr-restore-verify.dump"

cleanup() {
  "$pg_bin/dropdb" -h "$pg_socket" -p "$pg_port" --if-exists "$restore_database" >/dev/null 2>&1 || true
  rm -f "$backup_file"
}
trap cleanup EXIT

cleanup
"$pg_bin/pg_dump" -h "$pg_socket" -p "$pg_port" -d "$source_database" \
  --format=custom --no-owner --no-privileges --file="$backup_file"
"$pg_bin/createdb" -h "$pg_socket" -p "$pg_port" "$restore_database"
"$pg_bin/pg_restore" -h "$pg_socket" -p "$pg_port" -d "$restore_database" \
  --exit-on-error --single-transaction --no-owner --no-privileges "$backup_file"

fingerprint_sql="
select concat_ws('|',
  (select count(*) from patient),
  (select count(*) from encounter),
  (select count(*) from clinical_document),
  (select count(*) from clinical_document_version),
  (select count(*) from document_quality_run),
  (select count(*) from archive_case),
  (select count(*) from archive_case_item),
  (select count(*) from archive_case_event),
  (select count(*) from archive_export_package),
  (select coalesce(md5(string_agg(patient_id::text || ':' || row_version::text, ',' order by patient_id)), '') from patient),
  (select coalesce(md5(string_agg(document_version_id::text || ':' || content_hash, ',' order by document_version_id)), '') from clinical_document_version),
  (select coalesce(md5(string_agg(quality_run_id::text || ':' || content_hash || ':' || outcome, ',' order by quality_run_id)), '') from document_quality_run),
  (select coalesce(md5(string_agg(archive_case_id::text || ':' || manifest_hash || ':' || status, ',' order by archive_case_id)), '') from archive_case),
  (select coalesce(md5(string_agg(export_package_id::text || ':' || content_hash || ':' || byte_count::text, ',' order by export_package_id)), '') from archive_export_package),
  (select coalesce(md5(string_agg(event_hash, ',' order by audit_event_id)), '') from audit_event)
);"

source_fingerprint=$("$pg_bin/psql" -X -h "$pg_socket" -p "$pg_port" -d "$source_database" -tA -c "$fingerprint_sql")
restore_fingerprint=$("$pg_bin/psql" -X -h "$pg_socket" -p "$pg_port" -d "$restore_database" -tA -c "$fingerprint_sql")

if [[ "$source_fingerprint" != "$restore_fingerprint" ]]; then
  echo "Restore fingerprint mismatch" >&2
  exit 1
fi

echo "restore_verified fingerprint=$restore_fingerprint"
