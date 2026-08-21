#!/usr/bin/env bash
set -euo pipefail

MODE=collect
CONFIG=""
OUTPUT_DIR=""
DURATION_SECONDS=300
SAMPLES_FILE=""
EXPECTED_SAMPLES=""
INTERVAL_SECONDS=1
SOURCE_TIMEOUT_SECONDS=3
MAX_CLOCK_SKEW_MS=500
PRODUCT_LINE=LINEAR_PERPETUAL
ACTUATOR_URLS=""
EXPORTER_URL=""
CORE_URL=""
KAFKA_LAG_COMMAND=""
POSTGRES_WATERMARK_COMMAND=""
AERONSTAT_COMMAND=""
PROCESS_TARGETS=""
DISK_TARGETS=""
REQUIRED_ACTUATOR_NAMES=""
REQUIRED_PROCESS_NAMES=""
REQUIRED_DISK_NAMES=""
JCMD_BIN=jcmd
NMT_DIAGNOSTIC=false
TEMP_DIR=""
CHILD_PIDS=()

fail() { printf 'ERROR=%s\n' "$*" >&2; exit 2; }
is_uint() { [[ "$1" =~ ^[0-9]+$ ]]; }
require_uint() { is_uint "$2" || fail "$1 must be a non-negative integer"; }

cleanup() {
  local child
  set +u
  for child in "${CHILD_PIDS[@]}"; do kill "$child" 2>/dev/null || true; done
  for child in "${CHILD_PIDS[@]}"; do wait "$child" 2>/dev/null || true; done
  set -u
  [[ -z "$TEMP_DIR" || ! -d "$TEMP_DIR" ]] || rm -rf "$TEMP_DIR"
}
on_signal() {
  trap - EXIT INT TERM
  cleanup
  [[ -z "$OUTPUT_DIR" || ! -d "$OUTPUT_DIR" ]] || find "$OUTPUT_DIR" -maxdepth 1 -name '.sample-*.json' -type f -delete
  printf 'RESULT=ABORTED\n' >&2
  exit 130
}
trap 'status=$?; cleanup; exit "$status"' EXIT
trap on_signal INT TERM

usage() {
  printf '%s\n' 'usage: production-chain-telemetry.sh --config FILE --output-dir DIR [--duration-seconds N]' \
    '   or: production-chain-telemetry.sh --evaluate SAMPLES --expected-samples N --interval-seconds N --max-clock-skew-ms N --output-dir DIR'
}

while (($#)); do
  case "$1" in
    --config) CONFIG="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --duration-seconds) DURATION_SECONDS="${2:-}"; shift 2 ;;
    --evaluate) MODE=evaluate; SAMPLES_FILE="${2:-}"; shift 2 ;;
    --expected-samples) EXPECTED_SAMPLES="${2:-}"; shift 2 ;;
    --interval-seconds) INTERVAL_SECONDS="${2:-}"; shift 2 ;;
    --max-clock-skew-ms) MAX_CLOCK_SKEW_MS="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown argument=$1" ;;
  esac
done

parse_config() {
  local line key value number=0
  [[ -f "$CONFIG" ]] || fail "config missing path=$CONFIG"
  while IFS= read -r line || [[ -n "$line" ]]; do
    number=$((number + 1))
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || fail "malformed config line=$number"
    key="${BASH_REMATCH[1]}" value="${BASH_REMATCH[2]}"
    case "$key" in
      PRODUCT_LINE|INTERVAL_SECONDS|SOURCE_TIMEOUT_SECONDS|MAX_CLOCK_SKEW_MS|ACTUATOR_URLS|EXPORTER_URL|CORE_URL|KAFKA_LAG_COMMAND|POSTGRES_WATERMARK_COMMAND|AERONSTAT_COMMAND|PROCESS_TARGETS|DISK_TARGETS|REQUIRED_ACTUATOR_NAMES|REQUIRED_PROCESS_NAMES|REQUIRED_DISK_NAMES|JCMD_BIN|NMT_DIAGNOSTIC)
        printf -v "$key" '%s' "$value" ;;
      *) fail "unknown config key=$key line=$number" ;;
    esac
  done <"$CONFIG"
}

validate_pair_list() {
  local kind="$1" data="$2" entry name value
  IFS=';' read -r -a entries <<<"$data"
  for entry in "${entries[@]}"; do
    [[ "$entry" == *'|'* && "${entry#*|}" != *'|'* ]] || fail "malformed $kind target=$entry"
    name="${entry%%|*}"; value="${entry#*|}"
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && -n "$value" ]] || fail "invalid $kind target=$entry"
    case "$kind" in
      actuator) [[ "$value" =~ ^(https?|file):// ]] || fail "invalid actuator URL=$value" ;;
      process) is_uint "$value" || fail "invalid process pid=$value" ;;
      disk) [[ "$value" == /* ]] || fail "disk path must be absolute path=$value" ;;
    esac
  done
}

validate_name_list() {
  local kind="$1" data="$2" name seen="|"
  IFS=',' read -r -a names <<<"$data"
  ((${#names[@]} > 0)) || fail "$kind required names must not be empty"
  for name in "${names[@]}"; do
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail "invalid $kind required name=$name"
    [[ "$seen" != *"|$name|"* ]] || fail "duplicate $kind required name=$name"
    seen+="$name|"
  done
}

monotonic_ns() {
  perl -MTime::HiRes=clock_gettime,CLOCK_MONOTONIC -e 'printf "%.0f\n", clock_gettime(CLOCK_MONOTONIC) * 1_000_000_000'
}

wall_ms() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1_000'
}

run_timeout() {
  local seconds="$1" output="$2"
  shift 2
  perl -MPOSIX=setpgid -e '
    $seconds = shift @ARGV;
    $pid = fork();
    die "fork failed" unless defined $pid;
    if ($pid == 0) { setpgid(0, 0); exec @ARGV; exit 127; }
    setpgid($pid, $pid);
    $SIG{ALRM} = sub { kill "TERM", -$pid; waitpid($pid, 0); exit 124; };
    alarm $seconds;
    waitpid($pid, 0);
    alarm 0;
    exit($? == -1 ? 127 : $? >> 8);
  ' "$seconds" "$@" >"$output" 2>&1
}

metric_value() {
  local pattern="$1" file="$2"
  awk -v pattern="$pattern" '$0 !~ /^#/ && $1 ~ pattern {print $NF; exit}' "$file"
}

metric_summary() {
  local file="$1" first_pattern="$2" first_name="$3" second_pattern="$4" second_name="$5"
  local first_value second_value
  first_value="$(metric_value "$first_pattern" "$file")"
  second_value="$(metric_value "$second_pattern" "$file")"
  jq -cn --arg firstName "$first_name" --arg firstValue "$first_value" --arg secondName "$second_name" --arg secondValue "$second_value" '
    {($firstName):(if $firstValue=="" then null else ($firstValue|tonumber?) end),
     ($secondName):(if $secondValue=="" then null else ($secondValue|tonumber?) end)}'
}

source_wall_skew() {
  local file="$1" wall="$2" source_wall
  source_wall="$(metric_value '^surprising_telemetry_source_wall_time_millis($|\\{)' "$file")"
  if [[ -z "$source_wall" ]]; then printf '0\n'; else awk -v a="$wall" -v b="$source_wall" 'BEGIN {d=a-b; if(d<0)d=-d; printf "%.0f\n", d}'; fi
}

collect_url() {
  local name="$1" url="$2" kind="$3" wall="$4" destination="$5" ok=true reason="" skew=0
  local body="$TEMP_DIR/$name.body" metrics='{}'
  if ! run_timeout "$SOURCE_TIMEOUT_SECONDS" "$body" curl --fail --silent --show-error --max-time "$SOURCE_TIMEOUT_SECONDS" -- "$url"; then
    ok=false; reason=unreachable
  elif [[ ! -s "$body" ]]; then
    ok=false; reason=empty
  else
    case "$kind" in
      actuator)
        grep -Eq '^http_server_requests(_seconds)?' "$body" && grep -Eq '^jvm_' "$body" && grep -Eq '^process_' "$body" || { ok=false; reason=required_metrics_missing; }
        metrics="$(metric_summary "$body" '^http_server_requests(_seconds)?(_count)?($|\\{)' httpServerRequests '^jvm_memory_used_bytes($|\\{)' jvmMemoryUsedBytes)" ;;
      exporter)
        grep -q '^surprising_exporter_backlog_count' "$body" && grep -q '^surprising_exporter_last_acknowledged_sequence' "$body" && grep -q '^surprising_exporter_retries_total' "$body" || { ok=false; reason=required_metrics_missing; }
        metrics="$(metric_summary "$body" '^surprising_exporter_backlog_count($|\\{)' backlogCount '^surprising_exporter_last_acknowledged_sequence($|\\{)' lastAcknowledgedSequence)" ;;
      core)
        grep -q '^surprising_core_phase_' "$body" && grep -q '^surprising_core_positions' "$body" || { ok=false; reason=required_metrics_missing; }
        metrics="$(metric_summary "$body" '^surprising_core_positions($|\\{)' positions '^surprising_core_phase_prepare_count($|\\{)' prepareCount)" ;;
    esac
    skew="$(source_wall_skew "$body" "$wall")"
    if (( skew > MAX_CLOCK_SKEW_MS )); then ok=false; reason=clock_skew; fi
  fi
  jq -n --arg name "$name" --arg kind "$kind" --argjson ok "$ok" --arg reason "$reason" \
    --argjson clockSkewMillis "$skew" --argjson metrics "$metrics" \
    '{name:$name,kind:$kind,ok:$ok,reason:$reason,clockSkewMillis:$clockSkewMillis,metrics:$metrics}' >"$destination"
}

collect_command() {
  local name="$1" kind="$2" command="$3" destination="$4" ok=true reason=""
  local body="$TEMP_DIR/$name.body" metrics='{}' value
  if ! run_timeout "$SOURCE_TIMEOUT_SECONDS" "$body" "$command"; then ok=false; reason=command_failed_or_timed_out
  elif [[ ! -s "$body" ]]; then ok=false; reason=empty
  else
    case "$kind" in
      kafka)
        grep -q 'records-lag-max' "$body" && grep -qi 'watermark' "$body" || { ok=false; reason=required_metrics_missing; }
        value="$(grep -Eio 'records-lag-max[=:][0-9]+' "$body" | head -1 | sed -E 's/.*[=:]//' || true)"
        metrics="$(jq -cn --arg value "$value" '{recordsLagMax:(if $value=="" then null else ($value|tonumber?) end)}')" ;;
      postgres)
        grep -Eq '^[0-9]+$' "$body" || { ok=false; reason=invalid_watermark; }
        value="$(tr -d '[:space:]' <"$body")"
        metrics="$(jq -cn --arg value "$value" '{watermark:(if $value=="" then null else ($value|tonumber?) end)}')" ;;
      aeron)
        grep -qi 'cluster' "$body" && grep -qi 'archive' "$body" || { ok=false; reason=required_counters_missing; }
        value="$(grep -Eio 'mapped-bytes=[0-9]+' "$body" | head -1 | sed 's/.*=//' || true)"
        metrics="$(jq -cn --arg value "$value" '{mappedBytes:(if $value=="" then null else ($value|tonumber?) end)}')" ;;
    esac
  fi
  jq -n --arg name "$name" --arg kind "$kind" --argjson ok "$ok" --arg reason "$reason" --argjson metrics "$metrics" \
    '{name:$name,kind:$kind,ok:$ok,reason:$reason,metrics:$metrics}' >"$destination"
}

collect_process() {
  local name="$1" pid="$2" destination="$3" ok=true reason=""
  local process_file="$TEMP_DIR/$name.process" jcmd_file="$TEMP_DIR/$name.jcmd"
  local rss=0 cpu=0 threads=0 fds=0 heap_bytes="" buffer_pool_bytes="" nmt_committed_bytes="" diagnostic child jfr_checked=false
  local diagnostic_pids=()
  if ! kill -0 "$pid" 2>/dev/null; then ok=false; reason=process_absent
  else
    ps -p "$pid" -o rss= -o %cpu= >"$process_file" 2>&1 || { ok=false; reason=ps_failed; }
    read -r rss cpu <"$process_file" || { ok=false; reason=ps_parse_failed; }
    threads="$(ps -M "$pid" 2>/dev/null | awk 'NR>1 {count++} END {print count+0}')"
    if command -v lsof >/dev/null; then fds="$(lsof -p "$pid" 2>/dev/null | awk 'NR>1 {count++} END {print count+0}')"; else ok=false; reason=lsof_unavailable; fi
    : >"$jcmd_file"
    for diagnostic in GC.heap_info PerfCounter.print JFR.check; do run_timeout "$SOURCE_TIMEOUT_SECONDS" "$TEMP_DIR/$name.$diagnostic" "$JCMD_BIN" "$pid" "$diagnostic" & diagnostic_pids+=("$!"); done
    if [[ "$NMT_DIAGNOSTIC" == true ]]; then
      run_timeout "$SOURCE_TIMEOUT_SECONDS" "$TEMP_DIR/$name.VM.native_memory" "$JCMD_BIN" "$pid" VM.native_memory summary & diagnostic_pids+=("$!")
    fi
    for child in "${diagnostic_pids[@]}"; do wait "$child" || { ok=false; reason=jcmd_failed_or_timed_out; }; done
    for diagnostic in GC.heap_info PerfCounter.print JFR.check; do printf '\n[%s]\n' "$diagnostic" >>"$jcmd_file"; sed -n '1,240p' "$TEMP_DIR/$name.$diagnostic" >>"$jcmd_file"; done
    if [[ "$NMT_DIAGNOSTIC" == true ]]; then printf '\n[VM.native_memory summary]\n' >>"$jcmd_file"; sed -n '1,240p' "$TEMP_DIR/$name.VM.native_memory" >>"$jcmd_file"; fi
    grep -qi 'heap' "$jcmd_file" && grep -Eqi 'BufferPool|direct|mapped' "$jcmd_file" || { ok=false; reason=jcmd_required_metrics_missing; }
    if grep -Eqi 'No available recordings\.|Recording[[:space:]]+([0-9]+:|name=)|Recording:' "$TEMP_DIR/$name.JFR.check"; then
      jfr_checked=true
    else
      ok=false
      [[ -n "$reason" ]] || reason=jfr_check_invalid
    fi
    heap_bytes="$(grep -Eio 'used[ =][0-9]+([KMG])?' "$jcmd_file" | head -1 | sed -E 's/.*[ =]//' | awk '/K$/ {sub(/K$/,""); print $1*1024; next} /M$/ {sub(/M$/,""); print $1*1024*1024; next} /G$/ {sub(/G$/,""); print $1*1024*1024*1024; next} {print $1}')"
    buffer_pool_bytes="$(grep -Eio 'DirectBufferPool\.memoryUsed[=:][0-9]+' "$jcmd_file" | head -1 | sed -E 's/.*[=:]//' || true)"
    if [[ "$NMT_DIAGNOSTIC" == true ]]; then
      nmt_committed_bytes="$(grep -Eio 'Total:.*committed=[0-9]+([KMG])?' "$jcmd_file" | head -1 | grep -Eio 'committed=[0-9]+([KMG])?' | sed 's/committed=//' | awk '/K$/ {sub(/K$/,""); print $1*1024; next} /M$/ {sub(/M$/,""); print $1*1024*1024; next} /G$/ {sub(/G$/,""); print $1*1024*1024*1024; next} {print $1}' || true)"
    fi
  fi
  jq -n --arg name "$name" --argjson ok "$ok" --arg reason "$reason" \
    --argjson rssKib "${rss:-0}" --argjson cpuPercent "${cpu:-0}" --argjson threads "$threads" --argjson fileDescriptors "$fds" \
    --arg heapBytes "$heap_bytes" --arg bufferPoolBytes "$buffer_pool_bytes" --arg nmtCommittedBytes "$nmt_committed_bytes" \
    --argjson nmtDiagnostic "$NMT_DIAGNOSTIC" --argjson jfrChecked "$jfr_checked" \
    '{name:$name,ok:$ok,reason:$reason,rssKib:$rssKib,cpuPercent:$cpuPercent,threads:$threads,fileDescriptors:$fileDescriptors,
      heapUsedBytes:(if $heapBytes=="" then null else ($heapBytes|tonumber) end),bufferPoolBytes:(if $bufferPoolBytes=="" then null else ($bufferPoolBytes|tonumber) end),
      nmtCommittedBytes:(if $nmtCommittedBytes=="" then null else ($nmtCommittedBytes|tonumber) end),nmtDiagnostic:$nmtDiagnostic,jfrChecked:$jfrChecked}' >"$destination"
}

collect_disks() {
  local destination="$1" entry name path kib ok rows="$TEMP_DIR/disks.rows"
  : >"$rows"
  IFS=';' read -r -a entries <<<"$DISK_TARGETS"
  for entry in "${entries[@]}"; do
    name="${entry%%|*}"; path="${entry#*|}"; ok=true; kib=0
    if [[ "$name" == "$path" || ! -e "$path" ]]; then ok=false; else kib="$(du -sk "$path" | awk '{print $1}')" || ok=false; fi
    jq -cn --arg name "$name" --argjson ok "$ok" --argjson kib "$kib" '{name:$name,ok:$ok,kib:$kib}' >>"$rows"
  done
  jq -s '.' "$rows" >"$destination"
}

warmup_sources() {
  local entry name value command diagnostic
  IFS=';' read -r -a entries <<<"$ACTUATOR_URLS"
  for entry in "${entries[@]}"; do value="${entry#*|}"; curl --fail --silent --max-time "$SOURCE_TIMEOUT_SECONDS" -- "$value" >/dev/null 2>&1 || true; done
  curl --fail --silent --max-time "$SOURCE_TIMEOUT_SECONDS" -- "$EXPORTER_URL" >/dev/null 2>&1 || true
  curl --fail --silent --max-time "$SOURCE_TIMEOUT_SECONDS" -- "$CORE_URL" >/dev/null 2>&1 || true
  for command in "$KAFKA_LAG_COMMAND" "$POSTGRES_WATERMARK_COMMAND" "$AERONSTAT_COMMAND"; do run_timeout "$SOURCE_TIMEOUT_SECONDS" "$TEMP_DIR/warmup-command" "$command" || true; done
  IFS=';' read -r -a entries <<<"$PROCESS_TARGETS"
  for entry in "${entries[@]}"; do
    name="${entry%%|*}"; value="${entry#*|}"
    for diagnostic in GC.heap_info PerfCounter.print JFR.check; do run_timeout "$SOURCE_TIMEOUT_SECONDS" "$TEMP_DIR/warmup-$name" "$JCMD_BIN" "$value" "$diagnostic" || true; done
  done
}

evaluate() {
  local samples="$1" expected="$2" output="$3"
  [[ -s "$samples" ]] || fail "samples missing or empty path=$samples"
  mkdir -p "$output"
  local summary="$output/summary.json" report="$output/report.md"
  jq -s --argjson expected "$expected" --argjson interval "$INTERVAL_SECONDS" --argjson maxSkew "$MAX_CLOCK_SKEW_MS" '
    def all_sources_ok: all(.[]; .ok == true);
    def exact_names($rows;$required):
      ($required | length) > 0 and ([$rows[].name] | sort) == ($required | sort);
    def monotonic_ok: . as $s | all(range(1;length); $s[.].monotonicNanos > $s[.-1].monotonicNanos);
    def indexed_ok: . as $s | length == $expected and all(range(0;$expected); $s[.].index == .);
    def wall_aligned: . as $s | $s[0].wallTimeMillis as $start | all(range(0;length); (($s[.].wallTimeMillis - ($start + (. * $interval * 1000))) | fabs) <= $maxSkew);
    . as $samples | {
      schemaVersion:1, expectedSamples:$expected, actualSamples:length,
      checks:{indexedAndComplete:indexed_ok,monotonicClock:monotonic_ok,wallClockAligned:wall_aligned,
        allNamedLayers:all(.[];
          exact_names(.actuator;.requiredNames.actuator)
          and exact_names(.commands;.requiredNames.commands)
          and exact_names(.processes;.requiredNames.processes)
          and exact_names(.disks;.requiredNames.disks)),
        sourcesHealthy:all(.[]; (.actuator|all_sources_ok) and (.commands|all_sources_ok) and (.processes|all_sources_ok) and all(.disks[];.ok==true)),
        sourceClockSkew:all(.[]; all(.actuator[];.clockSkewMillis <= $maxSkew))},
      maxConsecutiveMissing:(if length == $expected then 0 else $expected-length end),
      rssCorrelation:{method:"RSS is correlated with JVM heap, BufferPool/direct, NMT diagnostic categories, and Aeron mapped/archive counters on the same sample index. Values are non-additive because NMT categories overlap JVM-managed regions.",
        nmtCoverageClaim:"NMT is diagnostic-only and does not cover arbitrary native libraries.",
        samples:[$samples[] | . as $sample | ($sample.commands[] | select(.kind=="aeron") | .metrics.mappedBytes) as $aeronMappedBytes |
          $sample.processes[] | {index:$sample.index,process:.name,rssBytes:(.rssKib*1024),heapUsedBytes,bufferPoolBytes,nmtCommittedBytes,
            aeronMappedBytes:$aeronMappedBytes}]}
    } | .result=(if all(.checks[]; . == true) then "PASS" else "FAIL" end)' "$samples" >"$summary"
  {
    printf '# Production-chain telemetry evaluation\n\n'
    printf -- '- Result: `%s`\n' "$(jq -r .result "$summary")"
    printf -- '- Samples: `%s/%s` at `%ss` cadence\n' "$(jq -r .actualSamples "$summary")" "$expected" "$INTERVAL_SECONDS"
    printf -- '- Checks: `%s`\n' "$(jq -c .checks "$summary")"
    printf -- '- RSS correlation: heap + BufferPool/direct + NMT diagnostic categories + Aeron mapped/archive counters are aligned by sample index. Residual RSS is retained explicitly.\n'
    printf -- '- Correlation rows: `%s` (non-additive because NMT overlaps JVM-managed regions).\n' "$(jq -r '.rssCorrelation.samples|length' "$summary")"
    printf -- '- NMT limitation: NMT is diagnostic-only and does not cover arbitrary native libraries.\n'
  } >"$report"
  [[ "$(jq -r .result "$summary")" == PASS ]] || return 1
}

collect_sample() {
  local index="$1" wall="$2" mono="$3" sample_temp="$4" sample_output="$5"
  local entry name value child
  TEMP_DIR="$sample_temp"
  mkdir -p "$TEMP_DIR"
  local actuator_rows="$TEMP_DIR/actuator.rows" command_rows="$TEMP_DIR/command.rows" process_rows="$TEMP_DIR/process.rows"
  : >"$actuator_rows"; : >"$command_rows"; : >"$process_rows"
  CHILD_PIDS=()
  IFS=';' read -r -a entries <<<"$ACTUATOR_URLS"
  for entry in "${entries[@]}"; do name="${entry%%|*}"; value="${entry#*|}"; collect_url "actuator-$name" "$value" actuator "$wall" "$TEMP_DIR/actuator-$name.json" & CHILD_PIDS+=("$!"); done
  collect_url exporter "$EXPORTER_URL" exporter "$wall" "$TEMP_DIR/exporter.json" & CHILD_PIDS+=("$!")
  collect_url core "$CORE_URL" core "$wall" "$TEMP_DIR/core.json" & CHILD_PIDS+=("$!")
  collect_command kafka kafka "$KAFKA_LAG_COMMAND" "$TEMP_DIR/kafka.json" & CHILD_PIDS+=("$!")
  collect_command postgres postgres "$POSTGRES_WATERMARK_COMMAND" "$TEMP_DIR/postgres.json" & CHILD_PIDS+=("$!")
  collect_command aeron aeron "$AERONSTAT_COMMAND" "$TEMP_DIR/aeron.json" & CHILD_PIDS+=("$!")
  IFS=';' read -r -a entries <<<"$PROCESS_TARGETS"
  for entry in "${entries[@]}"; do name="${entry%%|*}"; value="${entry#*|}"; collect_process "$name" "$value" "$TEMP_DIR/process-$name.json" & CHILD_PIDS+=("$!"); done
  collect_disks "$TEMP_DIR/disks.json"
  for child in "${CHILD_PIDS[@]}"; do wait "$child" || fail "collector worker failed pid=$child"; done
  CHILD_PIDS=()
  IFS=';' read -r -a entries <<<"$ACTUATOR_URLS"
  for entry in "${entries[@]}"; do name="${entry%%|*}"; jq -c . "$TEMP_DIR/actuator-$name.json" >>"$actuator_rows"; done
  jq -c . "$TEMP_DIR/exporter.json" >>"$actuator_rows"; jq -c . "$TEMP_DIR/core.json" >>"$actuator_rows"
  jq -c . "$TEMP_DIR/kafka.json" >>"$command_rows"; jq -c . "$TEMP_DIR/postgres.json" >>"$command_rows"; jq -c . "$TEMP_DIR/aeron.json" >>"$command_rows"
  IFS=';' read -r -a entries <<<"$PROCESS_TARGETS"
  for entry in "${entries[@]}"; do name="${entry%%|*}"; jq -c . "$TEMP_DIR/process-$name.json" >>"$process_rows"; done
  jq -cn --argjson index "$index" --argjson wall "$wall" --argjson mono "$mono" \
    --arg requiredActuator "$REQUIRED_ACTUATOR_NAMES" --arg requiredProcesses "$REQUIRED_PROCESS_NAMES" --arg requiredDisks "$REQUIRED_DISK_NAMES" \
    --slurpfile actuator "$actuator_rows" --slurpfile commands "$command_rows" --slurpfile processes "$process_rows" --slurpfile disks "$TEMP_DIR/disks.json" \
    '{index:$index,wallTimeMillis:$wall,monotonicNanos:$mono,
      requiredNames:{actuator:($requiredActuator|split(",")),commands:["kafka","postgres","aeron"],processes:($requiredProcesses|split(",")),disks:($requiredDisks|split(","))},
      actuator:$actuator,commands:$commands,processes:$processes,disks:$disks[0]}' >"$sample_output"
}

collect() {
  parse_config
  [[ "$PRODUCT_LINE" == LINEAR_PERPETUAL ]] || fail "PRODUCT_LINE must be LINEAR_PERPETUAL"
  require_uint INTERVAL_SECONDS "$INTERVAL_SECONDS"; ((INTERVAL_SECONDS == 1)) || fail 'INTERVAL_SECONDS must be 1'
  require_uint DURATION_SECONDS "$DURATION_SECONDS"; ((DURATION_SECONDS > 0)) || fail 'DURATION_SECONDS must be positive'
  require_uint SOURCE_TIMEOUT_SECONDS "$SOURCE_TIMEOUT_SECONDS"; ((SOURCE_TIMEOUT_SECONDS > 0)) || fail 'SOURCE_TIMEOUT_SECONDS must be positive'
  require_uint MAX_CLOCK_SKEW_MS "$MAX_CLOCK_SKEW_MS"
  [[ "$NMT_DIAGNOSTIC" == true || "$NMT_DIAGNOSTIC" == false ]] || fail 'NMT_DIAGNOSTIC must be true or false'
  [[ -n "$ACTUATOR_URLS" && -n "$EXPORTER_URL" && -n "$CORE_URL" && -n "$KAFKA_LAG_COMMAND" && -n "$POSTGRES_WATERMARK_COMMAND" && -n "$AERONSTAT_COMMAND" && -n "$PROCESS_TARGETS" && -n "$DISK_TARGETS" && -n "$REQUIRED_ACTUATOR_NAMES" && -n "$REQUIRED_PROCESS_NAMES" && -n "$REQUIRED_DISK_NAMES" ]] || fail 'all named telemetry sources and required names are required'
  validate_pair_list actuator "$ACTUATOR_URLS"; validate_pair_list process "$PROCESS_TARGETS"; validate_pair_list disk "$DISK_TARGETS"
  validate_name_list actuator "$REQUIRED_ACTUATOR_NAMES"; validate_name_list process "$REQUIRED_PROCESS_NAMES"; validate_name_list disk "$REQUIRED_DISK_NAMES"
  [[ "$EXPORTER_URL" =~ ^(https?|file):// && "$CORE_URL" =~ ^(https?|file):// ]] || fail 'exporter and Core URLs must use http, https, or file'
  command -v jq >/dev/null || fail 'jq unavailable'; command -v curl >/dev/null || fail 'curl unavailable'; command -v perl >/dev/null || fail 'perl unavailable'; command -v "$JCMD_BIN" >/dev/null || fail "jcmd unavailable binary=$JCMD_BIN"
  for command in "$KAFKA_LAG_COMMAND" "$POSTGRES_WATERMARK_COMMAND" "$AERONSTAT_COMMAND"; do [[ "$command" == /* && -x "$command" ]] || fail "adapter must be an absolute executable path=$command"; done
  [[ ! -e "$OUTPUT_DIR" ]] || fail "output path already exists path=$OUTPUT_DIR"
  mkdir -p "$OUTPUT_DIR"; TEMP_DIR="$(mktemp -d "$OUTPUT_DIR/.collector.XXXXXX")"
  local samples="$OUTPUT_DIR/samples.ndjson" start_mono start_wall index target now sleep_ms wall mono child
  warmup_sources
  start_mono="$(monotonic_ns)"; start_wall="$(wall_ms)"
  CHILD_PIDS=()
  for ((index=0; index<DURATION_SECONDS; index++)); do
    target=$((start_mono + index * 1000000000)); now="$(monotonic_ns)"
    if (( now < target )); then sleep_ms=$(((target-now)/1000000)); perl -MTime::HiRes=sleep -e 'sleep(shift(@ARGV) / 1000)' "$sleep_ms"; fi
    wall="$(wall_ms)"; mono="$(monotonic_ns)"
    collect_sample "$index" "$wall" "$mono" "$TEMP_DIR/sample-$index" "$OUTPUT_DIR/.sample-$index.json" & CHILD_PIDS+=("$!")
  done
  for child in "${CHILD_PIDS[@]}"; do wait "$child" || fail "sample worker failed pid=$child"; done
  CHILD_PIDS=()
  for ((index=0; index<DURATION_SECONDS; index++)); do jq -c . "$OUTPUT_DIR/.sample-$index.json" >>"$samples"; rm -f "$OUTPUT_DIR/.sample-$index.json"; done
  evaluate "$samples" "$DURATION_SECONDS" "$OUTPUT_DIR"
  printf 'RESULT=PASS samples=%s output=%s\n' "$DURATION_SECONDS" "$OUTPUT_DIR"
}

[[ -n "$OUTPUT_DIR" ]] || fail '--output-dir is required'
if [[ "$MODE" == evaluate ]]; then
  require_uint EXPECTED_SAMPLES "$EXPECTED_SAMPLES"; require_uint INTERVAL_SECONDS "$INTERVAL_SECONDS"; require_uint MAX_CLOCK_SKEW_MS "$MAX_CLOCK_SKEW_MS"
  evaluate "$SAMPLES_FILE" "$EXPECTED_SAMPLES" "$OUTPUT_DIR"
else
  [[ -n "$CONFIG" ]] || fail '--config is required'
  collect
fi
