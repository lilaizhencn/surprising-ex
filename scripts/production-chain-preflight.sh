#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
RUN_ID="${RUN_ID:-pcv-$(date -u +%Y%m%d%H%M%S)-$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')}"
RUNTIME_ROOT="${RUNTIME_ROOT:-$ROOT_DIR/.local-logs/production-chain}"
POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-$(id -un)}"
POSTGRES_CONTROL_DB="${POSTGRES_CONTROL_DB:-postgres}"
POSTGRES_DB="${POSTGRES_DB:-surprising_pcv_${RUN_ID//-/_}}"
PSQL_BIN="${PSQL_BIN:-psql}"
SHA256_BIN="${SHA256_BIN:-shasum}"
PROVENANCE_CHECKSUM_BIN="${PROVENANCE_CHECKSUM_BIN:-shasum}"
HOST_RAM_BYTES_OVERRIDE="${HOST_RAM_BYTES_OVERRIDE:-}"
DISK_AVAILABLE_KIB_OVERRIDE="${DISK_AVAILABLE_KIB_OVERRIDE:-}"
MIN_HOST_RAM_BYTES="${MIN_HOST_RAM_BYTES:-17179869184}"
ARCHIVE_GROWTH_GIB="${ARCHIVE_GROWTH_GIB:-96}"
OUTBOX_GROWTH_GIB="${OUTBOX_GROWTH_GIB:-24}"
KAFKA_GROWTH_GIB="${KAFKA_GROWTH_GIB:-48}"
POSTGRES_GROWTH_GIB="${POSTGRES_GROWTH_GIB:-32}"
LOG_PER_PROCESS_MIB="${LOG_PER_PROCESS_MIB:-500}"
JFR_PER_PROCESS_MIB="${JFR_PER_PROCESS_MIB:-1024}"
HEAP_DUMP_PER_PROCESS_MIB="${HEAP_DUMP_PER_PROCESS_MIB:-1024}"

RUN_DIR="$RUNTIME_ROOT/$RUN_ID"
MANIFEST_DIR="$RUN_DIR/manifest"
PROVENANCE_JSON="$MANIFEST_DIR/provenance.json"
PROVENANCE_CHECKSUM="$PROVENANCE_JSON.sha256"
TEMP_DIR=""
FINALIZED=false
RUN_PATH_CLAIMED=false
DATABASE_CREATED=false
RUN_PATH_ABSENT_AT_CHECK=false
DATABASE_ABSENT_AT_CHECK=false

fail() {
  printf 'ERROR=%s\n' "$*" >&2
  exit 2
}

is_uint() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

require_uint() {
  local name="$1" value="$2"
  is_uint "$value" || fail "$name must be a non-negative integer"
}

sha256_file() {
  "$SHA256_BIN" -a 256 "$1" | awk '{print $1}'
}

run_with_timeout() {
  local seconds="$1"
  shift
  perl -e 'alarm shift @ARGV; exec @ARGV' "$seconds" "$@"
}

write_abort_evidence() {
  local reason="$1" status="$2"
  [[ "$RUN_PATH_CLAIMED" == true && -d "$MANIFEST_DIR" && "$FINALIZED" != true ]] || return 0
  jq -n \
    --arg reason "$reason" \
    --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson status "$status" \
    '{schemaVersion: 1, result: "ABORTED", reason: $reason, timestampUtc: $timestamp, status: $status,
      abortEvidenceOrder: ["process-list", "host-resource-sample", "git-status-baseline", "logs", "JFR", "heap-dumps"]}' \
    >"$MANIFEST_DIR/abort.json" 2>/dev/null || true
}

on_signal() {
  local signal="$1"
  trap - EXIT INT TERM
  drop_reserved_database
  write_abort_evidence "signal=$signal" 130
  exit 130
}

drop_reserved_database() {
  [[ "$DATABASE_CREATED" == true && "$FINALIZED" != true ]] || return 0
  postgres -c "DROP DATABASE \"$POSTGRES_DB\"" >/dev/null 2>/dev/null || true
  DATABASE_CREATED=false
}

on_exit() {
  local status="$1"
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && "$FINALIZED" != true ]]; then
    drop_reserved_database
    write_abort_evidence 'preflight failure' "$status"
  fi
  exit "$status"
}

postgres() {
  run_with_timeout 10 "$PSQL_BIN" -X -v ON_ERROR_STOP=1 \
    -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_CONTROL_DB" "$@"
}

validate_inputs() {
  [[ "$PRODUCT_LINE" == LINEAR_PERPETUAL ]] || fail "PRODUCT_LINE must be LINEAR_PERPETUAL"
  [[ "$RUN_ID" =~ ^[a-z0-9][a-z0-9-]{7,47}$ ]] || fail "invalid RUN_ID=$RUN_ID"
  [[ "$RUNTIME_ROOT" = /* && "$RUNTIME_ROOT" != /tmp && "$RUNTIME_ROOT" != /tmp/* && "$RUNTIME_ROOT" != /var/folders/* ]] || fail "RUNTIME_ROOT must be an absolute durable path"
  [[ "$POSTGRES_DB" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || fail "invalid POSTGRES_DB=$POSTGRES_DB"
  require_uint MIN_HOST_RAM_BYTES "$MIN_HOST_RAM_BYTES"
  require_uint ARCHIVE_GROWTH_GIB "$ARCHIVE_GROWTH_GIB"
  require_uint OUTBOX_GROWTH_GIB "$OUTBOX_GROWTH_GIB"
  require_uint KAFKA_GROWTH_GIB "$KAFKA_GROWTH_GIB"
  require_uint POSTGRES_GROWTH_GIB "$POSTGRES_GROWTH_GIB"
  require_uint LOG_PER_PROCESS_MIB "$LOG_PER_PROCESS_MIB"
  require_uint JFR_PER_PROCESS_MIB "$JFR_PER_PROCESS_MIB"
  require_uint HEAP_DUMP_PER_PROCESS_MIB "$HEAP_DUMP_PER_PROCESS_MIB"
  [[ -z "$HOST_RAM_BYTES_OVERRIDE" ]] || require_uint HOST_RAM_BYTES_OVERRIDE "$HOST_RAM_BYTES_OVERRIDE"
  [[ -z "$DISK_AVAILABLE_KIB_OVERRIDE" ]] || require_uint DISK_AVAILABLE_KIB_OVERRIDE "$DISK_AVAILABLE_KIB_OVERRIDE"
  command -v jq >/dev/null || fail 'jq unavailable'
  command -v "$PSQL_BIN" >/dev/null || fail "psql unavailable binary=$PSQL_BIN"
  command -v "$SHA256_BIN" >/dev/null || fail "source checksum binary unavailable binary=$SHA256_BIN"
  command -v "$PROVENANCE_CHECKSUM_BIN" >/dev/null || fail "provenance checksum binary unavailable binary=$PROVENANCE_CHECKSUM_BIN"
}

collect_resource_budget() {
  mkdir -p "$RUNTIME_ROOT"
  TEMP_DIR="$(mktemp -d "$RUNTIME_ROOT/.preflight.XXXXXX")"
  local df_row
  df_row="$(df -kP "$RUNTIME_ROOT" | awk 'NR == 2 {print $2 " " $4}')"
  DISK_TOTAL_KIB="${df_row%% *}"
  DISK_AVAILABLE_KIB="${df_row##* }"
  [[ -n "$DISK_TOTAL_KIB" && -n "$DISK_AVAILABLE_KIB" ]] || fail 'unable to read disk inventory'
  if [[ -n "$DISK_AVAILABLE_KIB_OVERRIDE" ]]; then DISK_AVAILABLE_KIB="$DISK_AVAILABLE_KIB_OVERRIDE"; fi
  HOST_RAM_BYTES="$(sysctl -n hw.memsize)"
  if [[ -n "$HOST_RAM_BYTES_OVERRIDE" ]]; then HOST_RAM_BYTES="$HOST_RAM_BYTES_OVERRIDE"; fi
  require_uint HOST_RAM_BYTES "$HOST_RAM_BYTES"
  require_uint DISK_TOTAL_KIB "$DISK_TOTAL_KIB"
  require_uint DISK_AVAILABLE_KIB "$DISK_AVAILABLE_KIB"
  HOST_RAM_HEADROOM_BYTES=$((HOST_RAM_BYTES / 4))
  DISK_HEADROOM_BYTES=$((DISK_TOTAL_KIB * 1024 / 5))
  local minimum_disk_headroom=$((100 * 1024 * 1024 * 1024))
  (( DISK_HEADROOM_BYTES >= minimum_disk_headroom )) || DISK_HEADROOM_BYTES="$minimum_disk_headroom"
  local gib=$((1024 * 1024 * 1024)) mib=$((1024 * 1024))
  PROCESS_CAPS_FILE="$TEMP_DIR/process-caps.tsv"
  for node in 0 1 2; do printf 'core-node-%s\t768\t256\n' "$node"; done >"$PROCESS_CAPS_FILE"
  for service in instrument price account trading market-data derivatives-lifecycle funding gateway maker; do
    printf '%s\t384\t128\n' "$service" >>"$PROCESS_CAPS_FILE"
  done
  PROCESS_CAP_BYTES="$(awk -F '\t' '{sum += ($2 + $3) * 1024 * 1024} END {print sum}' "$PROCESS_CAPS_FILE")"
  GROWTH_ESTIMATE_BYTES=$(((ARCHIVE_GROWTH_GIB + OUTBOX_GROWTH_GIB + KAFKA_GROWTH_GIB + POSTGRES_GROWTH_GIB) * gib))
  LOG_BUDGET_BYTES=$((14 * LOG_PER_PROCESS_MIB * mib))
  JFR_BUDGET_BYTES=$((14 * JFR_PER_PROCESS_MIB * mib))
  HEAP_DUMP_BUDGET_BYTES=$((14 * HEAP_DUMP_PER_PROCESS_MIB * mib))
  PLANNED_DISK_BYTES=$((GROWTH_ESTIMATE_BYTES + LOG_BUDGET_BYTES + JFR_BUDGET_BYTES + HEAP_DUMP_BUDGET_BYTES))
  REQUIRED_DISK_BYTES=$((DISK_HEADROOM_BYTES + PLANNED_DISK_BYTES))
  AVAILABLE_DISK_BYTES=$((DISK_AVAILABLE_KIB * 1024))
  (( HOST_RAM_BYTES >= MIN_HOST_RAM_BYTES )) || fail "insufficient host RAM floor bytes=$HOST_RAM_BYTES required=$MIN_HOST_RAM_BYTES"
  (( PROCESS_CAP_BYTES + HOST_RAM_HEADROOM_BYTES <= HOST_RAM_BYTES )) || fail "insufficient RAM headroom caps=$PROCESS_CAP_BYTES headroom=$HOST_RAM_HEADROOM_BYTES host=$HOST_RAM_BYTES"
  (( AVAILABLE_DISK_BYTES >= REQUIRED_DISK_BYTES )) || fail "insufficient disk headroom available=$AVAILABLE_DISK_BYTES required=$REQUIRED_DISK_BYTES"
}

collect_hashes() {
  SOURCE_HASHES_FILE="$TEMP_DIR/source-hashes.tsv"
  CONFIG_HASHES_FILE="$TEMP_DIR/config-hashes.tsv"
  JAR_HASHES_FILE="$TEMP_DIR/jar-hashes.tsv"
  SOURCE_MISSING_FILE="$TEMP_DIR/source-missing.txt"
  CONFIG_MISSING_FILE="$TEMP_DIR/config-missing.txt"
  hash_tracked_files "$SOURCE_HASHES_FILE" "$SOURCE_MISSING_FILE" '*.java' '*.xml' '*.properties'
  hash_tracked_files "$CONFIG_HASHES_FILE" "$CONFIG_MISSING_FILE" '*.yml' '*.yaml' '*.conf' '*.sh'
  local jar_file
  while IFS= read -r jar_file; do printf '%s\t%s\n' "$(sha256_file "$jar_file")" "${jar_file#$ROOT_DIR/}"; done < <(find "$ROOT_DIR" -path '*/target/*.jar' -type f -print | LC_ALL=C sort) >"$JAR_HASHES_FILE"
  SOURCE_AGGREGATE_SHA256="$(sha256_file "$SOURCE_HASHES_FILE")"
  CONFIG_AGGREGATE_SHA256="$(sha256_file "$CONFIG_HASHES_FILE")"
  JAR_AGGREGATE_SHA256="$(sha256_file "$JAR_HASHES_FILE")"
}

hash_tracked_files() {
  local hashes_file="$1" missing_file="$2" paths_file relative_path
  shift 2
  : >"$hashes_file"
  : >"$missing_file"
  paths_file="$TEMP_DIR/$(basename "$hashes_file").paths"
  : >"$paths_file"
  while IFS= read -r relative_path; do
    if [[ -f "$ROOT_DIR/$relative_path" ]]; then
      printf '%s\n' "$ROOT_DIR/$relative_path" >>"$paths_file"
    else
      printf '%s\n' "$relative_path" >>"$missing_file"
    fi
  done < <(git -C "$ROOT_DIR" ls-files -- "$@" | LC_ALL=C sort)
  if [[ -s "$paths_file" ]]; then
    xargs -n 128 "$SHA256_BIN" -a 256 <"$paths_file" | \
      sed "s#  $ROOT_DIR/#\t#" | LC_ALL=C sort -k2,2 >"$hashes_file"
  fi
}

capture_git_baseline() {
  GIT_STATUS_FILE="$TEMP_DIR/git-status.before.txt"
  git -C "$ROOT_DIR" status --short >"$GIT_STATUS_FILE"
  DIRTY_PATCH_SHA256="$({
    git -C "$ROOT_DIR" diff --binary
    git -C "$ROOT_DIR" diff --cached --binary
  } | shasum -a 256 | awk '{print $1}')"
}

assert_run_path_absent() {
  [[ ! -e "$RUN_DIR" && ! -L "$RUN_DIR" ]] || fail "run path already exists path=$RUN_DIR"
  RUN_PATH_ABSENT_AT_CHECK=true
}

claim_run_path() {
  mkdir "$RUN_DIR"
  mkdir "$MANIFEST_DIR"
  RUN_PATH_CLAIMED=true
}

reserve_database() {
  local exists
  exists="$(postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '$POSTGRES_DB'")"
  [[ -z "$exists" ]] || fail "PostgreSQL database already exists database=$POSTGRES_DB"
  DATABASE_ABSENT_AT_CHECK=true
  postgres -c "CREATE DATABASE \"$POSTGRES_DB\"" >/dev/null
  DATABASE_CREATED=true
}

write_provenance() {
  local source_json config_json jar_json caps_json source_missing_json config_missing_json
  source_json="$(jq -Rn '[inputs | capture("^(?<sha>[0-9a-f]{64})\\t(?<path>.*)$")]' "$SOURCE_HASHES_FILE")"
  config_json="$(jq -Rn '[inputs | capture("^(?<sha>[0-9a-f]{64})\\t(?<path>.*)$")]' "$CONFIG_HASHES_FILE")"
  jar_json="$(jq -Rn '[inputs | capture("^(?<sha>[0-9a-f]{64})\\t(?<path>.*)$")]' "$JAR_HASHES_FILE")"
  caps_json="$(jq -Rn '[inputs | split("\t") | {name: .[0], heapMiB: (.[1] | tonumber), directMiB: (.[2] | tonumber)}]' "$PROCESS_CAPS_FILE")"
  source_missing_json="$(jq -Rn '[inputs | select(length > 0)]' "$SOURCE_MISSING_FILE")"
  config_missing_json="$(jq -Rn '[inputs | select(length > 0)]' "$CONFIG_MISSING_FILE")"
  cp "$GIT_STATUS_FILE" "$MANIFEST_DIR/git-status.before.txt"
  jq -n \
    --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg head "$(git -C "$ROOT_DIR" rev-parse HEAD)" \
    --arg branch "$(git -C "$ROOT_DIR" branch --show-current)" \
    --arg upstream "$(git -C "$ROOT_DIR" rev-parse --abbrev-ref '@{upstream}' 2>/dev/null || true)" \
    --arg dirtyPatchSha256 "$DIRTY_PATCH_SHA256" \
    --rawfile gitStatus "$GIT_STATUS_FILE" \
    --arg runId "$RUN_ID" --arg runPath "$RUN_DIR" --arg database "$POSTGRES_DB" \
    --arg postgresHost "$POSTGRES_HOST" --arg postgresPort "$POSTGRES_PORT" \
    --arg hostname "$(hostname)" --arg machine "$(uname -m)" \
    --arg osProduct "$(sw_vers -productName)" --arg osVersion "$(sw_vers -productVersion)" --arg osBuild "$(sw_vers -buildVersion)" \
    --arg jdk "$(java -version 2>&1 | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g')" \
    --arg clock "$(date '+%Y-%m-%dT%H:%M:%S%z')" --arg load "$(uptime)" \
    --argjson source "$source_json" --argjson config "$config_json" --argjson jars "$jar_json" --argjson caps "$caps_json" \
    --argjson sourceMissing "$source_missing_json" --argjson configMissing "$config_missing_json" \
    --arg sourceAggregate "$SOURCE_AGGREGATE_SHA256" --arg configAggregate "$CONFIG_AGGREGATE_SHA256" --arg jarAggregate "$JAR_AGGREGATE_SHA256" \
    --argjson cpu "$(sysctl -n hw.logicalcpu)" --argjson ram "$HOST_RAM_BYTES" \
    --argjson diskTotal "$((DISK_TOTAL_KIB * 1024))" --argjson diskAvailable "$AVAILABLE_DISK_BYTES" \
    --argjson ramHeadroom "$HOST_RAM_HEADROOM_BYTES" --argjson diskHeadroom "$DISK_HEADROOM_BYTES" \
    --argjson processCap "$PROCESS_CAP_BYTES" --argjson growth "$GROWTH_ESTIMATE_BYTES" \
    --argjson archiveGrowth "$((ARCHIVE_GROWTH_GIB * 1024 * 1024 * 1024))" \
    --argjson outboxGrowth "$((OUTBOX_GROWTH_GIB * 1024 * 1024 * 1024))" \
    --argjson kafkaGrowth "$((KAFKA_GROWTH_GIB * 1024 * 1024 * 1024))" \
    --argjson postgresGrowth "$((POSTGRES_GROWTH_GIB * 1024 * 1024 * 1024))" \
    --argjson logs "$LOG_BUDGET_BYTES" --argjson jfr "$JFR_BUDGET_BYTES" --argjson heapDump "$HEAP_DUMP_BUDGET_BYTES" \
    --argjson requiredDisk "$REQUIRED_DISK_BYTES" --argjson databaseCreated "$DATABASE_CREATED" \
    --argjson runPathAbsentAtCheck "$RUN_PATH_ABSENT_AT_CHECK" --argjson databaseAbsentAtCheck "$DATABASE_ABSENT_AT_CHECK" \
    '{schemaVersion: 1, generatedAtUtc: $generatedAt,
      run: {productLine: "LINEAR_PERPETUAL", id: $runId, path: $runPath, database: $database, databaseCreated: $databaseCreated,
            reservation: {runPathAbsentAtCheck: $runPathAbsentAtCheck, databaseAbsentAtCheck: $databaseAbsentAtCheck},
            postgres: {host: $postgresHost, port: $postgresPort}, ownership: "new-path-and-new-database-only"},
      git: {head: $head, branch: $branch, upstream: $upstream, statusShort: $gitStatus, dirtyPatchSha256: $dirtyPatchSha256},
      hashes: {source: {aggregateSha256: $sourceAggregate, files: $source, missingTrackedPaths: $sourceMissing}, config: {aggregateSha256: $configAggregate, files: $config, missingTrackedPaths: $configMissing}, jars: {aggregateSha256: $jarAggregate, files: $jars}},
      host: {hostname: $hostname, machine: $machine, os: {productName: $osProduct, version: $osVersion, build: $osBuild}, jdk: $jdk, cpuLogical: $cpu, ramBytes: $ram,
             disk: {totalBytes: $diskTotal, availableBytes: $diskAvailable}, clock: $clock, idleBaseline: {loadAverage: $load}},
      resourceBudgets: {hostRamHeadroomBytes: $ramHeadroom, diskHeadroomBytes: $diskHeadroom, processCaps: $caps,
             totalProcessHeapAndDirectCapBytes: $processCap,
             growthEstimatesBytes: {archive: $archiveGrowth, outbox: $outboxGrowth, kafka: $kafkaGrowth, postgres: $postgresGrowth},
             logBudgetBytes: $logs, jfrBudgetBytes: $jfr, heapDumpBudgetBytes: $heapDump, requiredFreeDiskBytes: $requiredDisk,
             abortEvidenceOrder: ["process-list", "host-resource-sample", "git-status-baseline", "logs", "JFR", "heap-dumps"]}}' \
    >"$PROVENANCE_JSON"
  if ! "$PROVENANCE_CHECKSUM_BIN" -a 256 "$PROVENANCE_JSON" >"$PROVENANCE_CHECKSUM"; then
    fail 'provenance checksum failed'
  fi
  [[ -s "$PROVENANCE_CHECKSUM" ]] || fail 'provenance checksum failed'
  chmod 0444 "$PROVENANCE_JSON" "$PROVENANCE_CHECKSUM" "$MANIFEST_DIR/git-status.before.txt"
  chmod 0555 "$MANIFEST_DIR"
}

main() {
  validate_inputs
  collect_resource_budget
  collect_hashes
  capture_git_baseline
  assert_run_path_absent
  trap 'on_signal INT' INT
  trap 'on_signal TERM' TERM
  trap 'on_exit $?' EXIT
  reserve_database
  claim_run_path
  write_provenance
  FINALIZED=true
  trap - EXIT INT TERM
  printf 'PREFLIGHT=PASS runId=%s runPath=%s database=%s manifest=%s\n' "$RUN_ID" "$RUN_DIR" "$POSTGRES_DB" "$PROVENANCE_JSON"
}

main "$@"
