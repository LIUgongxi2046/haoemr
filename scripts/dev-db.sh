#!/usr/bin/env bash
set -euo pipefail

action="${1:-status}"
pg_bin="${HAOEMR_PG_BIN:-/usr/local/opt/postgresql@18/bin}"
pg_data="${HAOEMR_PG_DATA:-/private/tmp/haoemr-pg18-data}"
pg_socket="${HAOEMR_PG_SOCKET:-/private/tmp}"
pg_port="${HAOEMR_PG_PORT:-55432}"
pg_log="${HAOEMR_PG_LOG:-/private/tmp/haoemr-pg18.log}"
pg_database="${HAOEMR_PG_DATABASE:-haoemr_dev}"

case "$action" in
  start)
    if [[ ! -f "$pg_data/PG_VERSION" ]]; then
      LC_ALL=C "$pg_bin/initdb" -D "$pg_data" --encoding=UTF8 --auth-local=trust --auth-host=trust
    fi
    if ! "$pg_bin/pg_isready" -h "$pg_socket" -p "$pg_port" >/dev/null 2>&1; then
      "$pg_bin/pg_ctl" -D "$pg_data" -l "$pg_log" -o "-p $pg_port -k $pg_socket" start
    fi
    if ! "$pg_bin/psql" -X -h "$pg_socket" -p "$pg_port" -d postgres -tAc \
      "select 1 from pg_database where datname = '$pg_database'" | grep -qx 1; then
      "$pg_bin/createdb" -h "$pg_socket" -p "$pg_port" "$pg_database"
    fi
    echo "database_ready name=$pg_database port=$pg_port socket=$pg_socket"
    ;;
  stop)
    if "$pg_bin/pg_isready" -h "$pg_socket" -p "$pg_port" >/dev/null 2>&1; then
      "$pg_bin/pg_ctl" -D "$pg_data" stop -m fast
    fi
    ;;
  status)
    "$pg_bin/pg_isready" -h "$pg_socket" -p "$pg_port"
    ;;
  *)
    echo "Usage: scripts/dev-db.sh start|stop|status" >&2
    exit 2
    ;;
esac
