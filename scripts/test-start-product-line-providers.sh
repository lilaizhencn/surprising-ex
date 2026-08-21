#!/usr/bin/env bash
set -euo pipefail

fake_java() {
  local argument name=background state_dir metrics_host metrics_port
  for argument in "$@"; do
    case "$argument" in
      *ClusterProbeMain) exit 0 ;;
      -Dsurprising.aeron.node-id=*) name="core-node${argument##*=}" ;;
      *ExporterMain) name=exporter ;;
      *ProjectionMain) name=projector ;;
    esac
  done
  if [[ -n "${SERVER_PORT:-}" ]]; then
    case "$SERVER_PORT" in
      9080) name=instrument ;;
      9081) name=market-data ;;
      9082) name=price ;;
      9084) name=trading ;;
      9086) name=account ;;
      9087) name=derivatives-lifecycle ;;
      9089) name=funding ;;
      9094) name=gateway ;;
      9096) name=maker ;;
    esac
  fi
  state_dir="${LAUNCHER_TEST_STATE:-$(cd "$(dirname "$0")/../.." && pwd)/state}"
  printf '%s\t%s\n' "$name" "$$" >> "$state_dir/started.tsv"
  if [[ "$name" == exporter ]]; then
    metrics_host="${EXPORTER_METRICS_HOST:-<unset>}"
    metrics_port="${EXPORTER_METRICS_PORT:-<unset>}"
    printf 'EXPORTER_METRICS_HOST=%s EXPORTER_METRICS_PORT=%s\n' "$metrics_host" "$metrics_port" \
      >> "$state_dir/exporter-metrics.tsv"
    [[ "$metrics_host" != '<unset>' && -n "$metrics_host" ]] || {
      printf 'FAKE_EXPORTER_CONFIG=FAIL missing EXPORTER_METRICS_HOST\n' >&2
      exit 64
    }
    [[ "$metrics_port" =~ ^[1-9][0-9]*$ ]] || {
      printf 'FAKE_EXPORTER_CONFIG=FAIL invalid EXPORTER_METRICS_PORT=%s\n' "$metrics_port" >&2
      exit 64
    }
    if [[ "$metrics_host" == quick-exit ]]; then
      /bin/sleep 0.2
      printf 'FAKE_EXPORTER_CONFIG=FAIL forced quick exit\n' >&2
      exit 65
    fi
  fi
  trap '' HUP
  trap 'exit 0' TERM INT
  while :; do /bin/sleep 1; done
}

fake_lsof() {
  local argument port='' service
  for argument in "$@"; do
    [[ "$argument" == -iTCP:* ]] && port="${argument#-iTCP:}"
  done
  case "$port" in
    9080) service=instrument ;;
    9081) service=market-data ;;
    9082) service=price ;;
    9084) service=trading ;;
    9086) service=account ;;
    9087) service=derivatives-lifecycle ;;
    9089) service=funding ;;
    9094) service=gateway ;;
    9096) service=maker ;;
    *) return 1 ;;
  esac
  [[ -f "$LAUNCHER_TEST_RUNTIME/$LAUNCHER_TEST_RUN_ID/pids/$service.pid" ]] || return 1
  sed -n '1p' "$LAUNCHER_TEST_RUNTIME/$LAUNCHER_TEST_RUN_ID/pids/$service.pid"
}

case "$(basename "$0")" in
  java) fake_java "$@"; exit ;;
  curl|nc) exit 0 ;;
  lsof) fake_lsof "$@"; exit ;;
  psql)
    [[ " $* " == *" SELECT to_regclass("* ]] && printf 't\n'
    exit 0
    ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCHER="$ROOT_DIR/scripts/start-product-line-providers.sh"
TEST_DRIVER="$ROOT_DIR/scripts/test-start-product-line-providers.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-launcher-test.XXXXXX")"
export LAUNCHER_TEST_STATE="$TEST_ROOT/state"
export LAUNCHER_TEST_RUNTIME="$TEST_ROOT/runtime"
export LAUNCHER_TEST_RUN_ID="launcher-fix-test-$$"
FAKE_BIN="$TEST_ROOT/bin"
FAKE_JAVA_HOME="$TEST_ROOT/java-home"
mkdir -p "$LAUNCHER_TEST_STATE" "$FAKE_BIN" "$FAKE_JAVA_HOME/bin"
for command in curl lsof nc psql; do ln -s "$TEST_DRIVER" "$FAKE_BIN/$command"; done
for command in bash mkdir ps rm rmdir sleep; do ln -s "/bin/$command" "$FAKE_BIN/$command"; done
cp "$TEST_DRIVER" "$FAKE_JAVA_HOME/bin/java"
chmod +x "$FAKE_JAVA_HOME/bin/java"

common_env=(
  PATH="$FAKE_BIN:/usr/bin:/usr/sbin"
  JAVA_HOME="$FAKE_JAVA_HOME"
  PRODUCT_LINE=LINEAR_PERPETUAL
  RUN_ID="$LAUNCHER_TEST_RUN_ID"
  RUNTIME_ROOT="$LAUNCHER_TEST_RUNTIME"
  POSTGRES_MODE=native
  BUILD_CHANGED=false
  JVM_XMS=16m
  JVM_XMX=16m
  LAUNCHER_TEST_STATE="$LAUNCHER_TEST_STATE"
  LAUNCHER_TEST_RUNTIME="$LAUNCHER_TEST_RUNTIME"
  LAUNCHER_TEST_RUN_ID="$LAUNCHER_TEST_RUN_ID"
)

launcher_env=(
  env
  "${common_env[@]}"
  EXPORTER_METRICS_HOST=127.0.0.1
  EXPORTER_METRICS_PORT=9191
)

operational_env=(
  env
  -u EXPORTER_METRICS_HOST
  -u EXPORTER_METRICS_PORT
  "${common_env[@]}"
)

missing_metrics_host_env=(
  env
  -u EXPORTER_METRICS_HOST
  "${common_env[@]}"
  EXPORTER_METRICS_PORT=9191
)

missing_metrics_port_env=(
  env
  -u EXPORTER_METRICS_PORT
  "${common_env[@]}"
  EXPORTER_METRICS_HOST=127.0.0.1
)

cleanup() {
  "${launcher_env[@]}" ACTION=down "$LAUNCHER" >/dev/null 2>&1 || true
  local label
  for label in $(/bin/launchctl list 2>/dev/null | awk -v prefix="com.surprising.product-line.${LAUNCHER_TEST_RUN_ID}." '$3 ~ "^" prefix {print $3}'); do
    /bin/launchctl remove "$label" >/dev/null 2>&1 || true
  done
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

UP_LOG="$TEST_ROOT/up.log"
OWNERSHIP_LOG="$TEST_ROOT/ownership.tsv"
STATUS_LOG="$TEST_ROOT/status.log"

status_must_fail() {
  local scenario="$1" scenario_log scenario_exit
  scenario_log="$TEST_ROOT/status-$scenario.log"
  set +e
  "${operational_env[@]}" ACTION=status "$LAUNCHER" > "$scenario_log" 2>&1
  scenario_exit=$?
  set -e
  printf '%s\n' "--- status adversary: $scenario ---"
  cat "$scenario_log"
  printf 'STATUS_CASE=%s EXIT=%s EXPECTED=NONZERO\n' "$scenario" "$scenario_exit"
  if [[ "$scenario_exit" == 0 ]]; then
    printf 'LAUNCHER_OWNERSHIP_REGRESSION=FAIL reason=status-false-pass scenario=%s\n' "$scenario" >&2
    exit 1
  fi
}

launcher_must_fail_before_ready() {
  local environment="$1" action="$2" scenario="$3" scenario_log scenario_exit
  local -a scenario_env
  shift 3
  scenario_log="$TEST_ROOT/launcher-$scenario.log"
  case "$environment" in
    startup) scenario_env=("${launcher_env[@]}") ;;
    missing-host) scenario_env=("${missing_metrics_host_env[@]}") ;;
    missing-port) scenario_env=("${missing_metrics_port_env[@]}") ;;
    *) printf 'unknown validation environment=%s\n' "$environment" >&2; exit 1 ;;
  esac
  set +e
  "${scenario_env[@]}" "$@" ACTION="$action" "$LAUNCHER" > "$scenario_log" 2>&1
  scenario_exit=$?
  set -e
  printf '%s\n' "--- launcher validation: $scenario ---"
  cat "$scenario_log"
  printf 'LAUNCHER_CASE=%s ACTION=%s EXIT=%s EXPECTED=NONZERO\n' "$scenario" "$action" "$scenario_exit"
  if [[ "$scenario_exit" == 0 ]] || grep -q '^READY=\|^PRODUCT_LINE_RUNTIME=PASS ' "$scenario_log"; then
    printf 'EXPORTER_METRICS_REGRESSION=FAIL reason=launcher-false-pass scenario=%s\n' "$scenario" >&2
    exit 1
  fi
}

/usr/bin/python3 - "$LAUNCHER" "$UP_LOG" "$OWNERSHIP_LOG" "${launcher_env[@]}" <<'PY'
import os
import signal
import subprocess
import sys
import time

launcher, up_log, ownership_log, *launcher_env = sys.argv[1:]
command = launcher_env + ["ACTION=up", launcher]
with open(up_log, "w", encoding="utf-8") as output:
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, preexec_fn=os.setsid)
    return_code = process.wait(timeout=60)
if return_code != 0:
    with open(up_log, encoding="utf-8") as output:
        sys.stderr.write(output.read())
    runtime = os.environ["LAUNCHER_TEST_RUNTIME"]
    run_id = os.environ["LAUNCHER_TEST_RUN_ID"]
    log_dir = os.path.join(runtime, run_id, "logs")
    if os.path.isdir(log_dir):
        for filename in sorted(os.listdir(log_dir)):
            path = os.path.join(log_dir, filename)
            if os.path.isfile(path):
                sys.stderr.write(f"--- {filename} ---\n")
                with open(path, encoding="utf-8") as service_log:
                    sys.stderr.write(service_log.read())
    raise SystemExit(f"ACTION=up failed with {return_code}")

runtime = os.environ["LAUNCHER_TEST_RUNTIME"]
run_id = os.environ["LAUNCHER_TEST_RUN_ID"]
pid_dir = os.path.join(runtime, run_id, "pids")
with open(ownership_log, "w", encoding="utf-8") as output:
    for filename in sorted(name for name in os.listdir(pid_dir) if name.endswith(".pid")):
        with open(os.path.join(pid_dir, filename), encoding="utf-8") as pid_file:
            pid = pid_file.read().strip()
        observed = subprocess.run(
            ["/bin/ps", "-p", pid, "-o", "pid=,ppid=,pgid=,sess=,comm="],
            check=False,
            capture_output=True,
            text=True,
        ).stdout.strip()
        output.write(f"{filename[:-4]}\t{pid}\t{observed}\n")

for sent_signal in (signal.SIGHUP, signal.SIGTERM):
    try:
        os.killpg(process.pid, sent_signal)
    except ProcessLookupError:
        pass
    time.sleep(0.5)
PY

cat "$UP_LOG"
printf '%s\n' '--- exporter metrics observed by mock ExporterMain ---'
cat "$LAUNCHER_TEST_STATE/exporter-metrics.tsv"
printf '%s\n' '--- PID ownership before caller-session teardown ---'
cat "$OWNERSHIP_LOG"
printf '%s\n' '--- persisted PID equals durable mock service PID ---'
pid_count="$(find "$LAUNCHER_TEST_RUNTIME/$LAUNCHER_TEST_RUN_ID/pids" -name '*.pid' | wc -l | tr -d ' ')"
owned_count="$(awk 'NR==FNR {started[$2]=1; next} $2 in started {matched++} END {print matched+0}' \
  "$LAUNCHER_TEST_STATE/started.tsv" "$OWNERSHIP_LOG")"
printf 'PID_COUNT=%s OWNED_PID_COUNT=%s\n' "$pid_count" "$owned_count"
[[ "$pid_count" == 14 && "$owned_count" == 14 ]]
grep -qx 'EXPORTER_METRICS_HOST=127.0.0.1 EXPORTER_METRICS_PORT=9191' \
  "$LAUNCHER_TEST_STATE/exporter-metrics.tsv"

set +e
"${operational_env[@]}" ACTION=status "$LAUNCHER" > "$STATUS_LOG" 2>&1
status_exit=$?
set -e
cat "$STATUS_LOG"
printf 'STATUS_ABSENT_METRICS_EXIT=%s\n' "$status_exit"

grep -q '^PRODUCT_LINE_RUNTIME=PASS ' "$UP_LOG"
[[ "$(grep -c '^READY=' "$UP_LOG")" == 15 ]]
if [[ "$status_exit" != 0 ]]; then
  printf 'LAUNCHER_OWNERSHIP_REGRESSION=FAIL reason=status-disagrees-after-session-exit\n' >&2
  exit 1
fi
if [[ "$(grep -c '^PROCESS=RUNNING ' "$STATUS_LOG")" != 14 ]]; then
  printf 'LAUNCHER_OWNERSHIP_REGRESSION=FAIL reason=owned-process-count\n' >&2
  exit 1
fi

for validation_action in up dry-run; do
  launcher_must_fail_before_ready missing-host "$validation_action" "missing-metrics-host-$validation_action"
  launcher_must_fail_before_ready missing-port "$validation_action" "missing-metrics-port-$validation_action"
  launcher_must_fail_before_ready startup "$validation_action" "empty-metrics-host-$validation_action" EXPORTER_METRICS_HOST=
  launcher_must_fail_before_ready startup "$validation_action" "empty-metrics-port-$validation_action" EXPORTER_METRICS_PORT=
  launcher_must_fail_before_ready startup "$validation_action" "non-numeric-metrics-port-$validation_action" EXPORTER_METRICS_PORT=not-a-port
  launcher_must_fail_before_ready startup "$validation_action" "out-of-range-metrics-port-$validation_action" EXPORTER_METRICS_PORT=65536
done

PID_DIR="$LAUNCHER_TEST_RUNTIME/$LAUNCHER_TEST_RUN_ID/pids"
MAKER_PID="$(<"$PID_DIR/maker.pid")"
MAKER_LABEL="$(<"$PID_DIR/maker.label")"
EXPORTER_LABEL="$(<"$PID_DIR/exporter.label")"

mv "$PID_DIR/maker.pid" "$TEST_ROOT/maker.pid"
status_must_fail missing-one-service
mv "$TEST_ROOT/maker.pid" "$PID_DIR/maker.pid"

mkdir "$TEST_ROOT/ownership-backup"
mv "$PID_DIR"/*.pid "$PID_DIR"/*.label "$TEST_ROOT/ownership-backup/"
status_must_fail empty-pid-set
mv "$TEST_ROOT/ownership-backup"/* "$PID_DIR/"
rmdir "$TEST_ROOT/ownership-backup"

printf '%s\n' "$$" > "$PID_DIR/maker.pid"
status_must_fail stale-reused-pid
printf '%s\n' "$MAKER_PID" > "$PID_DIR/maker.pid"

printf '%s\n' 'com.surprising.product-line.deliberately-wrong.maker' > "$PID_DIR/maker.label"
status_must_fail wrong-label
printf '%s\n' "$MAKER_LABEL" > "$PID_DIR/maker.label"

mv "$PID_DIR/maker.label" "$TEST_ROOT/maker.label"
status_must_fail missing-label
mv "$TEST_ROOT/maker.label" "$PID_DIR/maker.label"

QUICK_DEATH_LOG="$TEST_ROOT/status-quick-death.log"
set +e
"${operational_env[@]}" ACTION=status "$LAUNCHER" > "$QUICK_DEATH_LOG" 2>&1 &
quick_status_pid=$!
sleep 0.2
/bin/launchctl remove "$EXPORTER_LABEL" >/dev/null 2>&1
wait "$quick_status_pid"
quick_status_exit=$?
set -e
printf '%s\n' '--- status adversary: quick-death ---'
cat "$QUICK_DEATH_LOG"
printf 'STATUS_CASE=quick-death EXIT=%s EXPECTED=NONZERO\n' "$quick_status_exit"
if [[ "$quick_status_exit" == 0 ]]; then
  printf 'LAUNCHER_OWNERSHIP_REGRESSION=FAIL reason=status-false-pass scenario=quick-death\n' >&2
  exit 1
fi

"${operational_env[@]}" ACTION=down "$LAUNCHER"
[[ ! -e "$LAUNCHER_TEST_RUNTIME/active.lock/owner" ]]
[[ -z "$(find "$PID_DIR" -type f \( -name '*.pid' -o -name '*.label' \) -print -quit)" ]]
[[ -z "$(/bin/launchctl list 2>/dev/null | awk -v prefix="com.surprising.product-line.${LAUNCHER_TEST_RUN_ID}." '$3 ~ "^" prefix {print $3}')" ]]
printf 'ACTION_DOWN_ABSENT_METRICS_REGRESSION=PASS\n'

QUICK_EXPORTER_LOG="$TEST_ROOT/up-exporter-quick-exit.log"
set +e
"${launcher_env[@]}" EXPORTER_METRICS_HOST=quick-exit ACTION=up "$LAUNCHER" > "$QUICK_EXPORTER_LOG" 2>&1
quick_exporter_exit=$?
set -e
printf '%s\n' '--- exporter quick configuration exit ---'
cat "$QUICK_EXPORTER_LOG"
printf 'EXPORTER_QUICK_EXIT=%s EXPECTED=NONZERO\n' "$quick_exporter_exit"
if [[ "$quick_exporter_exit" == 0 ]] || grep -q '^READY=exporter$\|^PRODUCT_LINE_RUNTIME=PASS ' "$QUICK_EXPORTER_LOG"; then
  printf 'EXPORTER_METRICS_REGRESSION=FAIL reason=exporter-quick-exit-false-pass\n' >&2
  exit 1
fi
grep -q '^ERROR=service exited name=exporter ' "$QUICK_EXPORTER_LOG"
[[ ! -e "$LAUNCHER_TEST_RUNTIME/active.lock/owner" ]]

printf 'LAUNCHER_OWNERSHIP_REGRESSION=PASS\n'
