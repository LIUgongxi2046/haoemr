#!/usr/bin/env bash
set -euo pipefail

# One-time, additive transfer of patient and clinical-record facts from the
# pre-rename development database into the current HaoEMR development database.
# Existing rows in the target always win; this script never writes to the source.

pg_bin="${HAOEMR_PG_BIN:-/usr/local/opt/postgresql@18/bin}"
pg_host="${HAOEMR_PG_HOST:-127.0.0.1}"
pg_port="${HAOEMR_PG_PORT:-55432}"
source_database="${HAOEMR_LEGACY_DB:-openemr2026_dev}"
target_database="${HAOEMR_DB_DATABASE:-haoemr_dev}"
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [[ "$source_database" == "$target_database" ]]; then
  echo "Source and target databases must differ" >&2
  exit 2
fi

actual_target=$("$pg_bin/psql" -X -h "$pg_host" -p "$pg_port" -d "$target_database" -Atc 'select current_database()')
if [[ "$actual_target" != "$target_database" ]]; then
  echo "Target database verification failed: expected $target_database, got $actual_target" >&2
  exit 2
fi

source_user=$("$pg_bin/psql" -X -h "$pg_host" -p "$pg_port" -d "$source_database" -Atc 'select current_user')

"$pg_bin/psql" -X -v ON_ERROR_STOP=1 \
  -v source_database="$source_database" \
  -v source_user="$source_user" \
  -v target_database="$target_database" \
  -h "$pg_host" -p "$pg_port" -d "$target_database" \
  -f "$script_dir/sync-legacy-clinical-data.sql"
