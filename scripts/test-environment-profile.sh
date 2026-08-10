#!/usr/bin/env bash
set -euo pipefail

test_profile_cpu_count() {
  if command -v sysctl >/dev/null 2>&1; then
    sysctl -n hw.ncpu 2>/dev/null || true
  elif command -v getconf >/dev/null 2>&1; then
    getconf _NPROCESSORS_ONLN 2>/dev/null || true
  fi
}

test_profile_memory_mb() {
  if command -v sysctl >/dev/null 2>&1; then
    local bytes
    bytes="$(sysctl -n hw.memsize 2>/dev/null || true)"
    if [[ "${bytes}" =~ ^[0-9]+$ ]] && ((bytes > 0)); then
      echo $((bytes / 1024 / 1024))
      return
    fi
  fi
  if [[ -r /proc/meminfo ]]; then
    awk '/^MemTotal:/ {print int($2 / 1024); exit}' /proc/meminfo
    return
  fi
  echo 0
}

test_profile_container_memory_mb() {
  local value
  for file in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory/memory.limit_in_bytes; do
    [[ -r "${file}" ]] || continue
    value="$(tr -d '[:space:]' <"${file}")"
    if [[ "${value}" =~ ^[0-9]+$ ]] && ((value > 0 && value < 9223372036854771712)); then
      echo $((value / 1024 / 1024))
      return
    fi
  done
  echo 0
}

test_profile_detect() {
  local cpu memory container_memory
  local max_processes_default
  cpu="${TEST_CPU_COUNT:-$(test_profile_cpu_count)}"
  memory="${TEST_MEMORY_MB:-$(test_profile_memory_mb)}"
  container_memory="$(test_profile_container_memory_mb)"
  if [[ "${container_memory}" =~ ^[0-9]+$ ]] && ((container_memory > 0 && (memory == 0 || container_memory < memory))); then
    memory="${container_memory}"
  fi
  if ! [[ "${cpu}" =~ ^[0-9]+$ ]] || ((cpu < 1)); then cpu=1; fi
  if ! [[ "${memory}" =~ ^[0-9]+$ ]] || ((memory < 1)); then memory=4096; fi
  TEST_CPU_COUNT="${cpu}"
  TEST_MEMORY_MB="${memory}"

  if [[ "${TEST_PROFILE:-auto}" == "auto" ]]; then
    if ((cpu <= 4 || memory <= 8192)); then
      TEST_PROFILE="local-low"
    elif ((cpu <= 8 || memory <= 32768)); then
      TEST_PROFILE="local-standard"
    else
      TEST_PROFILE="cloud-capacity"
    fi
  fi
  case "${TEST_PROFILE}" in
    local-low)
      TEST_MAX_PROVIDER_PROCESSES="${TEST_MAX_PROVIDER_PROCESSES:-8}"
      TEST_JVM_HEAP_MB="${TEST_JVM_HEAP_MB:-512}"
      TEST_STRESS_SYMBOL_COUNT="${TEST_STRESS_SYMBOL_COUNT:-1}"
      TEST_STRESS_USER_COUNT="${TEST_STRESS_USER_COUNT:-100}"
      TEST_STRESS_LOAD_CONCURRENCY="${TEST_STRESS_LOAD_CONCURRENCY:-4}"
      TEST_TARGET_TPS="${TEST_TARGET_TPS:-10}"
      TEST_MATCHING_KAFKA_CONCURRENCY="${TEST_MATCHING_KAFKA_CONCURRENCY:-1}"
      TEST_MATCHING_ENGINE_SHARDS="${TEST_MATCHING_ENGINE_SHARDS:-1}"
      TEST_MATCHING_RISK_SHARDS="${TEST_MATCHING_RISK_SHARDS:-1}"
      TEST_ACCOUNT_KAFKA_CONCURRENCY="${TEST_ACCOUNT_KAFKA_CONCURRENCY:-1}"
      TEST_RISK_KAFKA_CONCURRENCY="${TEST_RISK_KAFKA_CONCURRENCY:-1}"
      TEST_MATCHING_MAX_POLL_RECORDS="${TEST_MATCHING_MAX_POLL_RECORDS:-100}"
      TEST_OUTBOX_BATCH_SIZE="${TEST_OUTBOX_BATCH_SIZE:-100}"
      TEST_OUTBOX_MAX_IN_FLIGHT="${TEST_OUTBOX_MAX_IN_FLIGHT:-32}"
      TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT="${TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT:-16}"
      TEST_RISK_OUTBOX_MAX_IN_FLIGHT="${TEST_RISK_OUTBOX_MAX_IN_FLIGHT:-16}"
      TEST_SERVICES="${TEST_SERVICES:-auto}"
      TEST_JVM_GC_AUTO="${TEST_JVM_GC_AUTO:-g1}"
      ;;
    local-standard)
      TEST_MAX_PROVIDER_PROCESSES="${TEST_MAX_PROVIDER_PROCESSES:-10}"
      TEST_JVM_HEAP_MB="${TEST_JVM_HEAP_MB:-1024}"
      TEST_STRESS_SYMBOL_COUNT="${TEST_STRESS_SYMBOL_COUNT:-20}"
      TEST_STRESS_USER_COUNT="${TEST_STRESS_USER_COUNT:-1000}"
      TEST_STRESS_LOAD_CONCURRENCY="${TEST_STRESS_LOAD_CONCURRENCY:-16}"
      TEST_TARGET_TPS="${TEST_TARGET_TPS:-50}"
      TEST_MATCHING_KAFKA_CONCURRENCY="${TEST_MATCHING_KAFKA_CONCURRENCY:-4}"
      TEST_MATCHING_ENGINE_SHARDS="${TEST_MATCHING_ENGINE_SHARDS:-4}"
      TEST_MATCHING_RISK_SHARDS="${TEST_MATCHING_RISK_SHARDS:-2}"
      TEST_ACCOUNT_KAFKA_CONCURRENCY="${TEST_ACCOUNT_KAFKA_CONCURRENCY:-8}"
      TEST_RISK_KAFKA_CONCURRENCY="${TEST_RISK_KAFKA_CONCURRENCY:-2}"
      TEST_MATCHING_MAX_POLL_RECORDS="${TEST_MATCHING_MAX_POLL_RECORDS:-500}"
      TEST_OUTBOX_BATCH_SIZE="${TEST_OUTBOX_BATCH_SIZE:-500}"
      TEST_OUTBOX_MAX_IN_FLIGHT="${TEST_OUTBOX_MAX_IN_FLIGHT:-64}"
      TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT="${TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT:-32}"
      TEST_RISK_OUTBOX_MAX_IN_FLIGHT="${TEST_RISK_OUTBOX_MAX_IN_FLIGHT:-32}"
      TEST_SERVICES="${TEST_SERVICES:-auto}"
      TEST_JVM_GC_AUTO="${TEST_JVM_GC_AUTO:-g1}"
      ;;
    cloud-capacity)
      TEST_MAX_PROVIDER_PROCESSES="${TEST_MAX_PROVIDER_PROCESSES:-16}"
      TEST_JVM_HEAP_MB="${TEST_JVM_HEAP_MB:-2048}"
      TEST_STRESS_SYMBOL_COUNT="${TEST_STRESS_SYMBOL_COUNT:-20}"
      TEST_STRESS_USER_COUNT="${TEST_STRESS_USER_COUNT:-5000}"
      TEST_STRESS_LOAD_CONCURRENCY="${TEST_STRESS_LOAD_CONCURRENCY:-128}"
      TEST_TARGET_TPS="${TEST_TARGET_TPS:-120}"
      TEST_MATCHING_KAFKA_CONCURRENCY="${TEST_MATCHING_KAFKA_CONCURRENCY:-16}"
      TEST_MATCHING_ENGINE_SHARDS="${TEST_MATCHING_ENGINE_SHARDS:-8}"
      TEST_MATCHING_RISK_SHARDS="${TEST_MATCHING_RISK_SHARDS:-4}"
      TEST_ACCOUNT_KAFKA_CONCURRENCY="${TEST_ACCOUNT_KAFKA_CONCURRENCY:-16}"
      TEST_RISK_KAFKA_CONCURRENCY="${TEST_RISK_KAFKA_CONCURRENCY:-4}"
      TEST_MATCHING_MAX_POLL_RECORDS="${TEST_MATCHING_MAX_POLL_RECORDS:-1000}"
      TEST_OUTBOX_BATCH_SIZE="${TEST_OUTBOX_BATCH_SIZE:-1000}"
      TEST_OUTBOX_MAX_IN_FLIGHT="${TEST_OUTBOX_MAX_IN_FLIGHT:-128}"
      TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT="${TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT:-64}"
      TEST_RISK_OUTBOX_MAX_IN_FLIGHT="${TEST_RISK_OUTBOX_MAX_IN_FLIGHT:-64}"
      TEST_SERVICES="${TEST_SERVICES:-auto}"
      TEST_JVM_GC_AUTO="${TEST_JVM_GC_AUTO:-g1}"
      ;;
    cloud-production)
      TEST_MAX_PROVIDER_PROCESSES="${TEST_MAX_PROVIDER_PROCESSES:-64}"
      TEST_JVM_HEAP_MB="${TEST_JVM_HEAP_MB:-4096}"
      TEST_STRESS_SYMBOL_COUNT="${TEST_STRESS_SYMBOL_COUNT:-20}"
      TEST_STRESS_USER_COUNT="${TEST_STRESS_USER_COUNT:-5000}"
      TEST_STRESS_LOAD_CONCURRENCY="${TEST_STRESS_LOAD_CONCURRENCY:-256}"
      TEST_TARGET_TPS="${TEST_TARGET_TPS:-3000}"
      TEST_MATCHING_KAFKA_CONCURRENCY="${TEST_MATCHING_KAFKA_CONCURRENCY:-32}"
      TEST_MATCHING_ENGINE_SHARDS="${TEST_MATCHING_ENGINE_SHARDS:-16}"
      TEST_MATCHING_RISK_SHARDS="${TEST_MATCHING_RISK_SHARDS:-8}"
      TEST_ACCOUNT_KAFKA_CONCURRENCY="${TEST_ACCOUNT_KAFKA_CONCURRENCY:-32}"
      TEST_RISK_KAFKA_CONCURRENCY="${TEST_RISK_KAFKA_CONCURRENCY:-8}"
      TEST_MATCHING_MAX_POLL_RECORDS="${TEST_MATCHING_MAX_POLL_RECORDS:-1000}"
      TEST_OUTBOX_BATCH_SIZE="${TEST_OUTBOX_BATCH_SIZE:-1000}"
      TEST_OUTBOX_MAX_IN_FLIGHT="${TEST_OUTBOX_MAX_IN_FLIGHT:-128}"
      TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT="${TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT:-128}"
      TEST_RISK_OUTBOX_MAX_IN_FLIGHT="${TEST_RISK_OUTBOX_MAX_IN_FLIGHT:-128}"
      TEST_SERVICES="${TEST_SERVICES:-auto}"
      TEST_JVM_GC_AUTO="${TEST_JVM_GC_AUTO:-g1}"
      ;;
    *)
      echo "Unsupported TEST_PROFILE=${TEST_PROFILE}" >&2
      return 1
      ;;
  esac
  ALLOW_RESOURCE_OVERRIDE="${ALLOW_RESOURCE_OVERRIDE:-false}"
  case "${ALLOW_RESOURCE_OVERRIDE}" in true|false) ;; *) echo "ALLOW_RESOURCE_OVERRIDE must be true or false" >&2; return 1 ;; esac
  for profile_value in TEST_MAX_PROVIDER_PROCESSES TEST_JVM_HEAP_MB TEST_STRESS_SYMBOL_COUNT TEST_STRESS_USER_COUNT TEST_STRESS_LOAD_CONCURRENCY TEST_TARGET_TPS; do
    [[ "${!profile_value}" =~ ^[1-9][0-9]*$ ]] || { echo "${profile_value} must be a positive integer" >&2; return 1; }
  done
  case "${TEST_PROFILE}" in
    local-low) max_processes_default=8 ;;
    local-standard) max_processes_default=10 ;;
    cloud-capacity) max_processes_default=16 ;;
    cloud-production) max_processes_default=64 ;;
  esac
  if ((TEST_MAX_PROVIDER_PROCESSES > max_processes_default)) && [[ "${ALLOW_RESOURCE_OVERRIDE}" != "true" ]]; then
    echo "TEST_MAX_PROVIDER_PROCESSES exceeds ${TEST_PROFILE} safe limit; set ALLOW_RESOURCE_OVERRIDE=true only with an approved environment override" >&2
    return 1
  fi
  if [[ "${TEST_JVM_HEAP_MB}" =~ ^[1-9][0-9]*$ && "${TEST_PROFILE}" != "cloud-production" ]]; then
    local heap_budget_percent=60
    case "${TEST_PROFILE}" in local-low) heap_budget_percent=45 ;; local-standard) heap_budget_percent=55 ;; esac
    if ((TEST_MEMORY_MB > 0 && TEST_JVM_HEAP_MB > TEST_MEMORY_MB * heap_budget_percent / 100)) && [[ "${ALLOW_RESOURCE_OVERRIDE}" != "true" ]]; then
      echo "TEST_JVM_HEAP_MB exceeds ${TEST_PROFILE} memory budget; set ALLOW_RESOURCE_OVERRIDE=true only with an approved environment override" >&2
      return 1
    fi
  fi
  TEST_SCALE_FACTOR="${TEST_SCALE_FACTOR:-1}"
  case "${TEST_JVM_GC:-auto}" in
    auto) TEST_JVM_GC="${TEST_JVM_GC_AUTO:-g1}" ;;
    g1|zgc|parallel) ;;
    *) echo "Unsupported TEST_JVM_GC=${TEST_JVM_GC}" >&2; return 1 ;;
  esac
  export TEST_PROFILE TEST_CPU_COUNT TEST_MEMORY_MB TEST_MAX_PROVIDER_PROCESSES TEST_JVM_HEAP_MB TEST_JVM_GC
  export TEST_STRESS_SYMBOL_COUNT TEST_STRESS_USER_COUNT TEST_STRESS_LOAD_CONCURRENCY TEST_TARGET_TPS
  export TEST_MATCHING_KAFKA_CONCURRENCY TEST_MATCHING_ENGINE_SHARDS TEST_MATCHING_RISK_SHARDS
  export TEST_ACCOUNT_KAFKA_CONCURRENCY TEST_RISK_KAFKA_CONCURRENCY TEST_SERVICES TEST_SCALE_FACTOR
  export TEST_MATCHING_MAX_POLL_RECORDS TEST_OUTBOX_BATCH_SIZE TEST_OUTBOX_MAX_IN_FLIGHT
  export TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT TEST_RISK_OUTBOX_MAX_IN_FLIGHT ALLOW_RESOURCE_OVERRIDE
}

test_profile_java_opts() {
  local heap="${TEST_JVM_HEAP_MB:-1024}"
  test_profile_java_opts_for_heap "${heap}"
}

test_profile_service_heap_mb() {
  local service="$1"
  local variable="TEST_$(echo "${service}" | tr '[:lower:]-' '[:upper:]_')_JVM_HEAP_MB"
  local override="${!variable:-}"
  if [[ "${override}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${override}"
    return
  fi
  local heap
  case "${TEST_PROFILE}" in
    local-low)
      case "${service}" in
        matching|account|margin-ops) heap=384 ;;
        price|trading-entry|edge|market-maker) heap=256 ;;
        *) heap=256 ;;
      esac
      ;;
    local-standard)
      case "${service}" in
        matching|account|margin-ops) heap=768 ;;
        price|trading-entry|edge|market-maker) heap=512 ;;
        *) heap=384 ;;
      esac
      ;;
    cloud-capacity)
      case "${service}" in
        matching|account|margin-ops) heap=1536 ;;
        price|trading-entry|edge|market-maker) heap=768 ;;
        *) heap=512 ;;
      esac
      ;;
    cloud-production)
      case "${service}" in
        matching|account|margin-ops) heap=3072 ;;
        price|trading-entry|edge|market-maker) heap=1536 ;;
        *) heap=1024 ;;
      esac
      ;;
    *)
      heap="${TEST_JVM_HEAP_MB:-1024}"
      ;;
  esac
  if [[ "${TEST_JVM_HEAP_MB:-}" =~ ^[1-9][0-9]*$ ]] && ((heap > TEST_JVM_HEAP_MB)); then
    heap="${TEST_JVM_HEAP_MB}"
  fi
  echo "${heap}"
}

test_profile_java_opts_for_service() {
  local service="$1"
  local heap
  heap="$(test_profile_service_heap_mb "${service}")"
  local options="$(test_profile_java_opts_for_heap "${heap}")"
  if [[ -n "${TEST_JFR_DIR:-}" ]]; then
    options+=" -XX:StartFlightRecording=filename=${TEST_JFR_DIR}/${service}.jfr,settings=profile,dumponexit=true"
  fi
  echo "${options}"
}

test_profile_java_args_for_service() {
  local service="$1"
  local heap
  heap="$(test_profile_service_heap_mb "${service}")"
  test_profile_java_args_for_heap "${heap}"
  if [[ -n "${TEST_JFR_DIR:-}" ]]; then
    printf '%s\n' "-XX:StartFlightRecording=filename=${TEST_JFR_DIR}/${service}.jfr,settings=profile,dumponexit=true"
  fi
}

test_profile_java_opts_for_heap() {
  local heap="$1"
  local options="" arg
  while IFS= read -r arg; do
    options+="${options:+ }${arg}"
  done < <(test_profile_java_args_for_heap "${heap}")
  echo "${options}"
}

test_profile_java_args_for_heap() {
  local heap="$1"
  case "${TEST_JVM_GC:-g1}" in
    g1)
      printf '%s\n' "-Xms${heap}m" "-Xmx${heap}m" "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=100" "-XX:+ExitOnOutOfMemoryError"
      ;;
    zgc)
      printf '%s\n' "-Xms${heap}m" "-Xmx${heap}m" "-XX:+UseZGC" "-XX:+ZGenerational" "-XX:+ExitOnOutOfMemoryError"
      ;;
    parallel)
      printf '%s\n' "-Xms${heap}m" "-Xmx${heap}m" "-XX:+UseParallelGC" "-XX:+ExitOnOutOfMemoryError"
      ;;
    *)
      echo "unsupported JVM GC ${TEST_JVM_GC}" >&2
      return 1
      ;;
  esac
}

test_profile_services() {
  local product_line="$1" scenario="${2:-trade}"
  if [[ "${TEST_SERVICES:-auto}" != "auto" ]]; then
    echo "${TEST_SERVICES}"
    return
  fi
  case "${scenario}" in
    matching) echo "instrument matching" ;;
    account) echo "instrument account" ;;
    websocket) echo "instrument price matching account trading-entry edge" ;;
    trade|recovery|soak|liquidation)
      if [[ "${product_line}" == "SPOT" ]]; then
        echo "instrument price matching account trading-entry edge market-maker"
      else
        echo "instrument price matching account margin-ops trading-entry edge market-maker"
      fi
      ;;
    *) echo "unsupported test scenario ${scenario}" >&2; return 1 ;;
  esac
}

test_profile_write_manifest() {
  local output="$1"
  mkdir -p "$(dirname "${output}")"
  {
    echo "profile=${TEST_PROFILE}"
    echo "cpu_count=${TEST_CPU_COUNT}"
    echo "memory_mb=${TEST_MEMORY_MB}"
    echo "max_provider_processes=${TEST_MAX_PROVIDER_PROCESSES}"
    echo "jvm_heap_mb=${TEST_JVM_HEAP_MB}"
    echo "matching_jvm_heap_mb=$(test_profile_service_heap_mb matching)"
    echo "account_jvm_heap_mb=$(test_profile_service_heap_mb account)"
    echo "margin_ops_jvm_heap_mb=$(test_profile_service_heap_mb margin-ops)"
    echo "jvm_gc=${TEST_JVM_GC}"
    echo "services=${TEST_SERVICES}"
    echo "stress_symbols=${TEST_STRESS_SYMBOL_COUNT}"
    echo "stress_users=${TEST_STRESS_USER_COUNT}"
    echo "stress_concurrency=${TEST_STRESS_LOAD_CONCURRENCY}"
    echo "target_tps=${TEST_TARGET_TPS}"
    echo "matching_kafka_concurrency=${TEST_MATCHING_KAFKA_CONCURRENCY}"
    echo "matching_engine_shards=${TEST_MATCHING_ENGINE_SHARDS}"
    echo "matching_risk_shards=${TEST_MATCHING_RISK_SHARDS}"
    echo "account_kafka_concurrency=${TEST_ACCOUNT_KAFKA_CONCURRENCY}"
    echo "risk_kafka_concurrency=${TEST_RISK_KAFKA_CONCURRENCY}"
    echo "matching_max_poll_records=${TEST_MATCHING_MAX_POLL_RECORDS}"
    echo "outbox_batch_size=${TEST_OUTBOX_BATCH_SIZE}"
    echo "outbox_max_in_flight=${TEST_OUTBOX_MAX_IN_FLIGHT}"
    echo "account_outbox_max_in_flight=${TEST_ACCOUNT_OUTBOX_MAX_IN_FLIGHT}"
    echo "risk_outbox_max_in_flight=${TEST_RISK_OUTBOX_MAX_IN_FLIGHT}"
    echo "scale_factor=${TEST_SCALE_FACTOR}"
  } >"${output}"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  TEST_PROFILE="${TEST_PROFILE:-auto}"
  test_profile_detect
  case "${1:-print}" in
    print) test_profile_write_manifest "${2:-/dev/stdout}" ;;
    services) test_profile_services "${2:?product line required}" "${3:-trade}" ;;
    *) echo "usage: $0 [print [file]|services PRODUCT_LINE [scenario]]" >&2; exit 2 ;;
  esac
fi
