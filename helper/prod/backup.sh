#!/usr/bin/env bash
# PostgreSQL backup/restore for per-node layer0 databases.
#
# One database per node: run this on EVERY validator host. Backups are plain
# pg_dump custom-format archives; verify each dump by restoring it into a
# throwaway database (postgres can restore a custom-format archive without
# touching the live DB).
#
# Usage:
#   bash helper/prod/backup.sh backup                     # dump DB_NAME to BACKUP_DIR
#   bash helper/prod/backup.sh verify <file.dump>         # test-restore into a scratch DB
#   bash helper/prod/backup.sh restore <file.dump>        # restore into DB_NAME (DANGEROUS)
#
# Env (defaults match helper/prod/validators/common.env):
#   DB_HOSTNAME=localhost DB_PORT=5432 DB_USERNAME=root DB_PASSWORD=...
#   DB_NAME=layer0
#   BACKUP_DIR=./backups
#
# Operational notes:
#   - Schedule `backup` from cron on each node: 0 2 * * * bash helper/prod/backup.sh backup
#   - Run `verify` at least weekly; a backup that cannot be restored is not a backup.
#   - Copy dumps off-host (rclone/scp to a second location) — a disk failure
#     that kills the live DB usually kills its local backups too.
#   - For point-in-time recovery, additionally enable WAL archiving in
#     postgresql.conf (archive_mode=on + archive_command) and snapshot the
#     resulting WAL dir together with the base dumps.
set -euo pipefail

DB_HOSTNAME="${DB_HOSTNAME:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:?set DB_PASSWORD}"
DB_NAME="${DB_NAME:-layer0}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
DATE="$(date +%Y%m%d-%H%M%S)"

export PGPASSWORD="${DB_PASSWORD}"

psql()    { command psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" "$@"; }
pg_dump() { command pg_dump -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" "$@"; }

cmd_backup() {
    mkdir -p "${BACKUP_DIR}"
    local out="${BACKUP_DIR}/${DB_NAME}-${DATE}.dump"
    echo "backing up ${DB_NAME} -> ${out}"
    pg_dump --format=custom --no-owner --no-privileges \
        --dbname "${DB_NAME}" --file "${out}"
    # The dump alone is not enough: verify it restores.
    echo "verifying ${out}"
    cmd_verify "${out}"
    echo "OK: ${out} (restore-verified)"
}

cmd_verify() {
    local dump="${1:?usage: backup.sh verify <file.dump>}"
    local scratch="scratch_verify_$$"
    trap 'psql -d postgres -c "DROP DATABASE IF EXISTS ${scratch};" >/dev/null 2>&1 || true' EXIT
    psql -d postgres -c "CREATE DATABASE ${scratch};" >/dev/null
    pg_restore --no-owner --no-privileges --exit-on-error \
        --dbname "${scratch}" "${dump}" >/dev/null
    echo "restore verification passed for ${dump}"
}

cmd_restore() {
    local dump="${1:?usage: backup.sh restore <file.dump>}"
    echo "WARNING: replacing ALL data in database ${DB_NAME} on ${DB_HOSTNAME}:${DB_PORT}"
    read -r -p "Type the database name to confirm: " confirm
    [ "${confirm}" = "${DB_NAME}" ] || { echo "aborted"; exit 1; }
    psql -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};" >/dev/null
    psql -d postgres -c "CREATE DATABASE ${DB_NAME};" >/dev/null
    pg_restore --no-owner --no-privileges --exit-on-error \
        --dbname "${DB_NAME}" "${dump}"
    echo "restore complete"
}

case "${1:-}" in
    backup)  cmd_backup ;;
    verify)  cmd_verify "${2:-}" ;;
    restore) cmd_restore "${2:-}" ;;
    *) echo "usage: backup.sh <backup|verify|restore> [file.dump]" >&2; exit 2 ;;
esac
