#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COLLECTOR="$ROOT_DIR/scripts/production-chain-telemetry.sh"
TEST_ROOT="${TASK4_TEST_ROOT:-$(mktemp -d "$ROOT_DIR/.local-logs/production-chain-telemetry.XXXXXX")}"
FIXTURES="$TEST_ROOT/fixtures"
sleep 600 & TEST_PROCESS_PID=$!
mkdir -p "$FIXTURES/disks/archive" "$FIXTURES/disks/kafka" "$FIXTURES/disks/postgres" "$FIXTURES/disks/outbox"

fail() { printf 'TEST_FAILURE=%s\n' "$*" >&2; exit 1; }
expect_failure() { local name="$1"; shift; if "$@" >"$TEST_ROOT/$name.out" 2>"$TEST_ROOT/$name.err"; then fail "expected failure name=$name"; fi; }
cleanup() { kill "$TEST_PROCESS_PID" 2>/dev/null || true; wait "$TEST_PROCESS_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

printf '%s\n' 'http_server_requests_seconds_count{authorization="Bearer TASK4_AUTH_SECRET"} 2' 'jvm_memory_used_bytes{password="TASK4_PASSWORD_SECRET"} 1024' 'jvm_buffer_memory_used_bytes{id="direct",token="TASK4_TOKEN_SECRET"} 256' 'process_cpu_usage 0.1' >"$FIXTURES/actuator.prom"
printf '%s\n' 'surprising_exporter_backlog_count{token="TASK4_EXPORTER_TOKEN_SECRET"} 0' 'surprising_exporter_last_acknowledged_sequence 8' 'surprising_exporter_retries_total 0' >"$FIXTURES/exporter.prom"
printf '%s\n' 'surprising_core_phase_prepare_count 1' 'surprising_core_phase_exchange_count 1' 'surprising_core_phase_apply_count 1' 'surprising_core_positions{password="TASK4_CORE_PASSWORD_SECRET"} 2' >"$FIXTURES/core.prom"
printf '%s\n' 'http_server_requests_seconds_count 2' 'jvm_memory_used_bytes 1024' 'process_cpu_usage 0.1' 'surprising_telemetry_source_wall_time_millis 1' >"$FIXTURES/skew.prom"

for adapter in kafka postgres aeron jcmd; do
  adapter_path="$FIXTURES/$adapter"
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    'case "$(basename "$0")" in' \
    '  kafka) printf "%s\n" "records-lag-max=0 watermark=8" ;;' \
    '  postgres) printf "8\n" ;;' \
    '  aeron) printf "%s\n" "cluster commit-position=8" "archive recording-position=8 mapped-bytes=4096" ;;' \
    '  jcmd) if [[ " $* " == *" JFR.check "* ]]; then printf "%s\n" "No available recordings."; elif [[ " $* " == *" VM.native_memory "* ]]; then printf "%s\n" "Total: reserved=2048K, committed=1536K"; else printf "%s\n" "heap used 1024K" "sun.nio.ch.DirectBufferPool.memoryUsed=256" "mapped BufferPool=4096 TASK4_JCMD_SECRET"; fi ;;' \
    'esac' >"$adapter_path"
  chmod +x "$adapter_path"
done

write_config() {
  local path="$1" actuator="$2" kafka="$3"
  printf '%s\n' \
    'PRODUCT_LINE=LINEAR_PERPETUAL' 'INTERVAL_SECONDS=1' 'SOURCE_TIMEOUT_SECONDS=2' 'MAX_CLOCK_SKEW_MS=500' \
    "ACTUATOR_URLS=gateway|$actuator" \
    "EXPORTER_URL=file://$FIXTURES/exporter.prom" "CORE_URL=file://$FIXTURES/core.prom" \
    "KAFKA_LAG_COMMAND=$kafka" "POSTGRES_WATERMARK_COMMAND=$FIXTURES/postgres" "AERONSTAT_COMMAND=$FIXTURES/aeron" \
    "PROCESS_TARGETS=collector-test|$TEST_PROCESS_PID" \
    "DISK_TARGETS=archive|$FIXTURES/disks/archive;kafka|$FIXTURES/disks/kafka;postgres|$FIXTURES/disks/postgres;outbox|$FIXTURES/disks/outbox" \
    'REQUIRED_ACTUATOR_NAMES=actuator-gateway,exporter,core' \
    'REQUIRED_PROCESS_NAMES=collector-test' \
    'REQUIRED_DISK_NAMES=archive,kafka,postgres,outbox' \
    "JCMD_BIN=$FIXTURES/jcmd" 'NMT_DIAGNOSTIC=false' >"$path"
}

write_config "$FIXTURES/happy.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/kafka"
"$COLLECTOR" --config "$FIXTURES/happy.env" --output-dir "$TEST_ROOT/happy" --duration-seconds 3 >"$TEST_ROOT/happy.out"
jq -e '.result=="PASS" and .actualSamples==3 and all(.checks[];.==true)' "$TEST_ROOT/happy/summary.json" >/dev/null || fail 'happy summary contract failed'
jq -e 'all(.processes[];.nmtDiagnostic==false and .nmtCommittedBytes==null and .jfrChecked==true)' "$TEST_ROOT/happy/samples.ndjson" >/dev/null || fail 'normal mode diagnostic contract failed'

for jfr_mode in empty malformed; do
  jcmd_path="$FIXTURES/jcmd-$jfr_mode"
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    'if [[ " $* " == *" JFR.check "* ]]; then' \
    "  [[ \"$jfr_mode\" == empty ]] || printf '%s\\n' 'JFR command completed without recording state'" \
    'elif [[ " $* " == *" VM.native_memory "* ]]; then' \
    '  printf "%s\n" "Total: reserved=2048K, committed=1536K"' \
    'else' \
    '  printf "%s\n" "heap used 1024K" "sun.nio.ch.DirectBufferPool.memoryUsed=256" "mapped BufferPool=4096"' \
    'fi' >"$jcmd_path"
  chmod +x "$jcmd_path"
  write_config "$FIXTURES/jfr-$jfr_mode.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/kafka"
  printf 'JCMD_BIN=%s\n' "$jcmd_path" >>"$FIXTURES/jfr-$jfr_mode.env"
  expect_failure "jfr-$jfr_mode" "$COLLECTOR" --config "$FIXTURES/jfr-$jfr_mode.env" --output-dir "$TEST_ROOT/jfr-$jfr_mode" --duration-seconds 1
  jq -e '.result=="FAIL" and .checks.sourcesHealthy==false' "$TEST_ROOT/jfr-$jfr_mode/summary.json" >/dev/null || fail "invalid JFR output was accepted mode=$jfr_mode"
  jq -e 'all(.processes[];.jfrChecked==false and .ok==false and .reason=="jfr_check_invalid")' "$TEST_ROOT/jfr-$jfr_mode/samples.ndjson" >/dev/null || fail "invalid JFR process health was accepted mode=$jfr_mode"
done

! rg -q 'TASK4_(AUTH|PASSWORD|TOKEN|EXPORTER_TOKEN|CORE_PASSWORD)_SECRET' "$TEST_ROOT/happy/samples.ndjson" || fail 'telemetry artifact leaked sensitive metric label'
jq -e 'all(.actuator[]; ((has("url") | not) and (has("body") | not))) and all(.commands[]; ((has("command") | not) and (has("body") | not))) and all(.processes[]; ((has("pid") | not) and (has("jcmd") | not))) and all(.disks[]; (has("path") | not))' "$TEST_ROOT/happy/samples.ndjson" >/dev/null || fail 'telemetry artifact retained raw source fields'

cp "$FIXTURES/kafka" "$FIXTURES/TASK4_COMMAND_PATH_SECRET"; chmod +x "$FIXTURES/TASK4_COMMAND_PATH_SECRET"
mkdir -p "$FIXTURES/disks/TASK4_DISK_PATH_SECRET"
write_config "$FIXTURES/secret-locations.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/TASK4_COMMAND_PATH_SECRET"
printf '%s\n' \
  "DISK_TARGETS=archive|$FIXTURES/disks/archive;kafka|$FIXTURES/disks/kafka;postgres|$FIXTURES/disks/postgres;outbox|$FIXTURES/disks/TASK4_DISK_PATH_SECRET" \
  'ACTUATOR_URLS=gateway|file://TASK4_USERINFO_SECRET:TASK4_URL_PASSWORD_SECRET@/unreachable' >>"$FIXTURES/secret-locations.env"
expect_failure secret-locations "$COLLECTOR" --config "$FIXTURES/secret-locations.env" --output-dir "$TEST_ROOT/secret-locations" --duration-seconds 1
! rg -q 'TASK4_.*_SECRET' "$TEST_ROOT/secret-locations/samples.ndjson" || fail 'telemetry artifact leaked secret location'

if [[ "${TASK4_EXPECT_STRICT_NAMES:-false}" == true ]]; then
  write_config "$FIXTURES/underspecified.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/kafka"
  printf '%s\n' 'REQUIRED_ACTUATOR_NAMES=actuator-gateway,exporter,core-0,core-1,core-2' \
    'REQUIRED_PROCESS_NAMES=exporter,core-0,core-1,core-2,kafka,postgres,aeron' \
    'REQUIRED_DISK_NAMES=archive,outbox,kafka,postgres' >>"$FIXTURES/underspecified.env"
  expect_failure underspecified "$COLLECTOR" --config "$FIXTURES/underspecified.env" \
    --output-dir "$TEST_ROOT/underspecified" --duration-seconds 1
  jq -e '.result=="FAIL" and .checks.allNamedLayers==false' "$TEST_ROOT/underspecified/summary.json" >/dev/null \
    || fail 'underspecified names were not rejected'
fi

sed 's/^NMT_DIAGNOSTIC=false$/NMT_DIAGNOSTIC=true/' "$FIXTURES/happy.env" >"$FIXTURES/nmt.env"
"$COLLECTOR" --config "$FIXTURES/nmt.env" --output-dir "$TEST_ROOT/nmt" --duration-seconds 1 >"$TEST_ROOT/nmt.out"
jq -e '.processes[0].nmtDiagnostic==true and .processes[0].nmtCommittedBytes==1572864' "$TEST_ROOT/nmt/samples.ndjson" >/dev/null || fail 'NMT diagnostic mode was not captured'

jq -c 'select(.index != 1)' "$TEST_ROOT/happy/samples.ndjson" >"$FIXTURES/missing.ndjson"
expect_failure missing "$COLLECTOR" --evaluate "$FIXTURES/missing.ndjson" --expected-samples 3 --interval-seconds 1 --max-clock-skew-ms 500 --output-dir "$TEST_ROOT/missing"
jq -e '.result=="FAIL" and .checks.indexedAndComplete==false' "$TEST_ROOT/missing/summary.json" >/dev/null || fail 'missing interval was not rejected'

write_config "$FIXTURES/skew.env" "file://$FIXTURES/skew.prom" "$FIXTURES/kafka"
expect_failure skew "$COLLECTOR" --config "$FIXTURES/skew.env" --output-dir "$TEST_ROOT/skew" --duration-seconds 1
jq -e '.result=="FAIL" and .checks.sourceClockSkew==false' "$TEST_ROOT/skew/summary.json" >/dev/null || fail 'clock skew was not rejected'

write_config "$FIXTURES/denied.env" 'file:///definitely/missing/task4.prom' "$FIXTURES/kafka"
expect_failure denied "$COLLECTOR" --config "$FIXTURES/denied.env" --output-dir "$TEST_ROOT/denied" --duration-seconds 1
jq -e '.result=="FAIL" and .checks.sourcesHealthy==false' "$TEST_ROOT/denied/summary.json" >/dev/null || fail 'endpoint denial was not rejected'

printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$FIXTURES/empty"; chmod +x "$FIXTURES/empty"
write_config "$FIXTURES/empty.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/empty"
expect_failure empty "$COLLECTOR" --config "$FIXTURES/empty.env" --output-dir "$TEST_ROOT/empty" --duration-seconds 1
jq -e '.result=="FAIL" and .checks.sourcesHealthy==false' "$TEST_ROOT/empty/summary.json" >/dev/null || fail 'empty successful adapter was not rejected'

printf '%s\n' '#!/usr/bin/env bash' 'sleep 60' >"$FIXTURES/hung"; chmod +x "$FIXTURES/hung"
write_config "$FIXTURES/hung.env" "file://$FIXTURES/actuator.prom" "$FIXTURES/hung"
started="$(date +%s)"; expect_failure hung "$COLLECTOR" --config "$FIXTURES/hung.env" --output-dir "$TEST_ROOT/hung" --duration-seconds 1
(( $(date +%s) - started < 10 )) || fail 'hung adapter timeout was not bounded'

printf '%s\n' 'NOT_VALID_CONFIG' >"$FIXTURES/malformed.env"
expect_failure malformed "$COLLECTOR" --config "$FIXTURES/malformed.env" --output-dir "$TEST_ROOT/malformed" --duration-seconds 1

marker="$FIXTURES/injected"; cp "$FIXTURES/happy.env" "$FIXTURES/injection.env"; printf 'UNKNOWN=$(touch %s)\n' "$marker" >>"$FIXTURES/injection.env"
expect_failure injection "$COLLECTOR" --config "$FIXTURES/injection.env" --output-dir "$TEST_ROOT/injection" --duration-seconds 1
[[ ! -e "$marker" ]] || fail 'config content was shell-evaluated'

for interruption in 1 2; do
  "$COLLECTOR" --config "$FIXTURES/happy.env" --output-dir "$TEST_ROOT/interrupted-$interruption" --duration-seconds 10 >"$TEST_ROOT/interrupted-$interruption.out" 2>"$TEST_ROOT/interrupted-$interruption.err" & collector_pid=$!
  sleep 1; kill -TERM "$collector_pid"; if wait "$collector_pid"; then fail 'interrupted collector reported success'; fi
  ! find "$TEST_ROOT/interrupted-$interruption" -name '.collector.*' -type d | grep -q . || fail 'collector temp directory leaked'
  ! find "$TEST_ROOT/interrupted-$interruption" -name '.sample-*.json' -type f | grep -q . || fail 'collector partial sample leaked'
done
"$COLLECTOR" --config "$FIXTURES/happy.env" --output-dir "$TEST_ROOT/resumed" --duration-seconds 1 >"$TEST_ROOT/resumed.out"
jq -e '.result=="PASS"' "$TEST_ROOT/resumed/summary.json" >/dev/null || fail 'resume in a new run directory failed'

printf 'CONTRACT_TEST=PASS root=%s\n' "$TEST_ROOT"
