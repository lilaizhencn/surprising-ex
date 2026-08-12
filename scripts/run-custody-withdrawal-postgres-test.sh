#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DB_NAME="surprising_withdrawal_spring_it_${RANDOM}_$$"
DB_HOST="${SURPRISING_WITHDRAWAL_IT_DATABASE_HOST:-localhost}"
DB_PORT="${SURPRISING_WITHDRAWAL_IT_DATABASE_PORT:-5432}"
DB_USER="${SURPRISING_WITHDRAWAL_IT_DATABASE_USER:-$(whoami)}"
export PGPASSWORD="${SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD:-}"

cleanup() {
    dropdb -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" --if-exists "${DB_NAME}" >/dev/null
}
trap cleanup EXIT

createdb -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" "${DB_NAME}"
cd "${PROJECT_ROOT}"
SURPRISING_WITHDRAWAL_IT_DATABASE_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=disable" \
SURPRISING_WITHDRAWAL_IT_DATABASE_USER="${DB_USER}" \
SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD="${SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD:-}" \
    mvn -q -pl surprising-gateway -am \
    -Dtest=CustodyWithdrawalReconciliationPostgresTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
