#!/usr/bin/env bash
set -euo pipefail

if [[ "${PSQL_FAKE:-false}" == true ]]; then
  [[ -n "${PSQL_FAKE_STATE:-}" && -n "${POSTGRES_DB:-}" ]] || exit 64
  mkdir -p "$PSQL_FAKE_STATE"
  [[ -z "${PSQL_FAKE_LOG:-}" ]] || printf '%s\n' "$*" >>"$PSQL_FAKE_LOG"
  if [[ "${PSQL_FAKE_BLOCK:-false}" == true ]]; then
    : >"$PSQL_FAKE_STATE/entered"
    while true; do sleep 1; done
  fi
  case " $* " in
    *" SELECT 1 FROM pg_database "*)
      [[ -e "$PSQL_FAKE_STATE/$POSTGRES_DB" ]] && printf '1\n'
      ;;
    *" CREATE DATABASE "*)
      [[ ! -e "$PSQL_FAKE_STATE/$POSTGRES_DB" ]] || exit 1
      : >"$PSQL_FAKE_STATE/$POSTGRES_DB"
      ;;
    *) exit 64 ;;
  esac
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFLIGHT="$ROOT_DIR/scripts/production-chain-preflight.sh"
TEST_ROOT="${TASK1_TEST_ROOT:-$(mktemp -d "$ROOT_DIR/.local-logs/production-chain-contract.XXXXXX")}"
RUN_ID="pcv-contract-$(date -u +%Y%m%d%H%M%S)-$$"
RUNTIME_ROOT="$TEST_ROOT/runs"
DATABASE_NAME="surprising_pcv_contract_${$}"
RESULT_DIR="$TEST_ROOT/results"
FAKE_PSQL_STATE="$TEST_ROOT/fake-postgres"
FAKE_PSQL_LOG="$TEST_ROOT/fake-postgres.log"
mkdir -p "$RESULT_DIR" "$FAKE_PSQL_STATE"
export PRODUCT_LINE=LINEAR_PERPETUAL RUN_ID RUNTIME_ROOT
export POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5432
export POSTGRES_USER="${POSTGRES_USER:-$(id -un)}" POSTGRES_CONTROL_DB=postgres POSTGRES_DB="$DATABASE_NAME"
export PSQL_BIN="$0" PSQL_FAKE=true PSQL_FAKE_STATE="$FAKE_PSQL_STATE" PSQL_FAKE_LOG="$FAKE_PSQL_LOG"

fail() {
  printf 'TEST_FAILURE=%s\n' "$*" >&2
  exit 1
}

expect_failure() {
  local name="$1"
  shift
  if "$@" >"$RESULT_DIR/$name.out" 2>"$RESULT_DIR/$name.err"; then
    fail "expected failure name=$name"
  fi
}

run_preflight() {
  env "$PREFLIGHT"
}

[[ -x "$PREFLIGHT" ]] || fail "preflight script missing path=$PREFLIGHT"

run_preflight >"$RESULT_DIR/happy.out" 2>"$RESULT_DIR/happy.err"
MANIFEST="$RUNTIME_ROOT/$RUN_ID/manifest/provenance.json"
CHECKSUM="$MANIFEST.sha256"
jq -e \
  '.schemaVersion == 1 and .git.head != "" and .git.dirtyPatchSha256 != "" and
   .run.id == env.RUN_ID and .run.database == env.POSTGRES_DB and
   .run.reservation.runPathAbsentAtCheck == true and
   .run.reservation.databaseAbsentAtCheck == true and
   .resourceBudgets.hostRamHeadroomBytes > 0 and
   .resourceBudgets.diskHeadroomBytes > 0 and
   (.resourceBudgets.processCaps | length) > 0 and
   (.hashes.source.aggregateSha256 | length) == 64 and
   (.hashes.config.aggregateSha256 | length) == 64 and
   (.host.hostname | length) > 0 and (.host.machine | length) > 0 and
   (.host.os.productName | length) > 0 and .host.idleBaseline.loadAverage != ""' \
  "$MANIFEST" >/dev/null || fail "manifest contract failed path=$MANIFEST"
[[ -s "$CHECKSUM" ]] || fail "manifest checksum missing path=$CHECKSUM"
shasum -a 256 -c "$CHECKSUM" >/dev/null || fail "manifest checksum invalid path=$CHECKSUM"

expect_failure repeat run_preflight
grep -Fq 'run path already exists' "$RESULT_DIR/repeat.err" || fail 'repeat refusal reason missing'
[[ "$(wc -l <"$FAKE_PSQL_LOG")" -eq 2 ]] || fail 'repeat reached PostgreSQL after run path collision'

expect_failure malformed env RUN_ID='bad/id' "$PREFLIGHT"
grep -Fq 'invalid RUN_ID' "$RESULT_DIR/malformed.err" || fail 'invalid run id refusal reason missing'

expect_failure numeric env RUN_ID="pcv-numeric-$$" RUNTIME_ROOT="$TEST_ROOT/numeric" POSTGRES_DB="surprising_pcv_numeric_${$}" HOST_RAM_BYTES_OVERRIDE=not-a-number "$PREFLIGHT"
grep -Fq 'HOST_RAM_BYTES_OVERRIDE must be a non-negative integer' "$RESULT_DIR/numeric.err" || fail 'numeric refusal reason missing'

expect_failure disk env RUN_ID="pcv-disk-$$" RUNTIME_ROOT="$TEST_ROOT/disk" POSTGRES_DB="surprising_pcv_disk_${$}" DISK_AVAILABLE_KIB_OVERRIDE=1 "$PREFLIGHT"
grep -Fq 'insufficient disk headroom' "$RESULT_DIR/disk.err" || fail 'disk refusal reason missing'
[[ "$(wc -l <"$FAKE_PSQL_LOG")" -eq 2 ]] || fail 'disk refusal reached PostgreSQL'

expect_failure checksum env RUN_ID="pcv-checksum-$$" RUNTIME_ROOT="$TEST_ROOT/checksum" POSTGRES_DB="surprising_pcv_checksum_${$}" PROVENANCE_CHECKSUM_BIN=/usr/bin/false "$PREFLIGHT"
grep -Fq 'provenance checksum failed' "$RESULT_DIR/checksum.err" || fail 'checksum refusal reason missing'
! grep -Fq 'PREFLIGHT=PASS' "$RESULT_DIR/checksum.out" || fail 'checksum failure printed success'

DATABASE_RUN_ID="pcv-db-$$"
expect_failure database env RUN_ID="$DATABASE_RUN_ID" RUNTIME_ROOT="$TEST_ROOT/db" POSTGRES_DB="$DATABASE_NAME" "$PREFLIGHT"
grep -Fq 'PostgreSQL database already exists' "$RESULT_DIR/database.err" || fail 'database refusal reason missing'
[[ ! -e "$TEST_ROOT/db/$DATABASE_RUN_ID" ]] || fail 'database refusal created run path'

printf 'CONTRACT_TEST=PASS root=%s runId=%s database=%s\n' "$TEST_ROOT" "$RUN_ID" "$DATABASE_NAME"
