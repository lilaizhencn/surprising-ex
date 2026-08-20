#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --stage NAME --attempt-dir DIR --benchmark-suite baseline --forks N [--maker-depth N] [--jfr-settings profile]" >&2
}

stage=""
attempt_dir=""
benchmark_suite=""
forks=""
maker_depth="1"
jfr_settings="profile"
while (($#)); do
  case "$1" in
    --stage) stage="${2-}"; shift 2 ;;
    --attempt-dir) attempt_dir="${2-}"; shift 2 ;;
    --benchmark-suite) benchmark_suite="${2-}"; shift 2 ;;
    --forks) forks="${2-}"; shift 2 ;;
    --maker-depth) maker_depth="${2-}"; shift 2 ;;
    --jfr-settings) jfr_settings="${2-}"; shift 2 ;;
    *) usage; echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$stage" =~ ^[a-z0-9][a-z0-9-]*$ ]] || [[ -z "$attempt_dir" ]]; then
  usage
  exit 2
fi
if [[ "$benchmark_suite" != "baseline" ]]; then
  echo "unsupported benchmark suite: $benchmark_suite" >&2
  exit 2
fi
if [[ ! "$forks" =~ ^[1-9][0-9]*$ ]]; then
  echo "forks must be a positive integer" >&2
  exit 2
fi
if [[ "$maker_depth" != "1" && "$maker_depth" != "10" && "$maker_depth" != "100" ]]; then
  echo "maker-depth must be one of 1, 10, or 100" >&2
  exit 2
fi
if [[ "$jfr_settings" != "profile" && "$jfr_settings" != "default" ]]; then
  echo "jfr-settings must be profile or default" >&2
  exit 2
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 25 is required (JAVA_HOME must name a JDK)" >&2
  exit 25
fi

java_properties=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1)
property() { printf '%s\n' "$java_properties" | awk -F'= ' -v key="$1" '$0 ~ "^[[:space:]]*" key " =" {print $2; exit}'; }
feature_version=$(property java.specification.version)
java_vendor=$(property java.vendor)
vm_name=$(property java.vm.name)
runtime_version=$(property java.runtime.version)
if [[ "$feature_version" != "25" || "$java_vendor" != "Eclipse Adoptium" \
  || ( "$vm_name" != *HotSpot* && "$vm_name" != "OpenJDK 64-Bit Server VM" ) \
  || ! -x "$JAVA_HOME/bin/jfr" ]]; then
  echo "Temurin 25 required: feature=${feature_version:-unknown} vendor=${java_vendor:-unknown} vm=${vm_name:-unknown} runtime=${runtime_version:-unknown}" >&2
  exit 25
fi

selected_java_home="$JAVA_HOME"
java_bin="$selected_java_home/bin/java"
jfr_bin="$selected_java_home/bin/jfr"
java_build=$("$java_bin" -version 2>&1 | tr '\n' ' ' | sed 's/[[:space:]]*$//')
java_flag_args=(-Xms256m -Xmx256m -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED)
java_flags="${java_flag_args[*]}"
repo_root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$attempt_dir"
attempt_dir=$(cd "$attempt_dir" && pwd)

child_pid=""
cleanup() {
  if [[ -n "$child_pid" ]] && kill -0 "$child_pid" 2>/dev/null; then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

rm -f "$attempt_dir/$stage-result.json" "$attempt_dir/$stage-result.json.tmp" \
  "$attempt_dir/jfr-summary.txt" "$attempt_dir/jfr-hot-methods.txt" \
  "$attempt_dir/jfr-allocation-by-site.txt" "$attempt_dir/jfr-gc.txt" \
  "$attempt_dir/jfr-safepoints.txt"
find "$attempt_dir" -maxdepth 1 -type f \( -name "$stage-fork-*.jfr" -o -name "$stage-fork-*.log" \) -delete

build_log="$attempt_dir/$stage-build.log"
(cd "$repo_root" && mvn -pl surprising-aeron-core/surprising-aeron-tools -am \
  -DskipTests clean package) >"$build_log" 2>&1
jar="$repo_root/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
[[ -s "$jar" ]] || { echo "tools benchmark jar is missing" >&2; exit 1; }

: >"$attempt_dir/jfr-summary.txt"
: >"$attempt_dir/jfr-hot-methods.txt"
: >"$attempt_dir/jfr-allocation-by-site.txt"
: >"$attempt_dir/jfr-gc.txt"
: >"$attempt_dir/jfr-safepoints.txt"

rates=()
p50s=()
p99s=()
p999s=()
orderss=()
matched_quantitys=()
queue_depths=()
pending_depths=()
timeout_seconds="${STAGE_FORK_TIMEOUT_SECONDS:-180}"
for ((fork=1; fork<=forks; fork++)); do
  seed=$((9900 + fork))
  fork_log="$attempt_dir/$stage-fork-$fork.log"
  recording="$attempt_dir/$stage-fork-$fork.jfr"
  "$java_bin" "${java_flag_args[@]}" \
    -XX:StartFlightRecording="filename=$recording,settings=$jfr_settings,dumponexit=true" \
    -cp "$jar" com.surprising.aeron.tools.ClusterCapacityMain --local-baseline "$seed" "$maker_depth" \
    >"$fork_log" 2>&1 &
  child_pid=$!
  started_at=$SECONDS
  while kill -0 "$child_pid" 2>/dev/null; do
    if ((SECONDS - started_at >= timeout_seconds)); then
      kill "$child_pid" 2>/dev/null || true
      wait "$child_pid" 2>/dev/null || true
      child_pid=""
      echo "benchmark fork $fork exceeded ${timeout_seconds}s" >&2
      exit 124
    fi
    sleep 1
  done
  if ! wait "$child_pid"; then
    child_pid=""
    echo "benchmark fork $fork failed; see $fork_log" >&2
    exit 1
  fi
  child_pid=""

  for marker in exchangeCoreConcurrentBenchmark coreAcceptFreezeBenchmark inMemoryCoreBenchmark \
                coreAcceptFreezeConcurrentBenchmark perpetualEndToEndBenchmark clusterCapacityBaseline; do
    grep -q "${marker}=PASS" "$fork_log" || {
      echo "benchmark fork $fork is missing ${marker}=PASS" >&2
      exit 1
    }
  done
  [[ -s "$recording" ]] || { echo "benchmark fork $fork did not produce JFR" >&2; exit 1; }

  "$jfr_bin" summary "$recording" | sed "1i\\
===== fork $fork =====" >>"$attempt_dir/jfr-summary.txt"
  "$jfr_bin" view --width 200 hot-methods "$recording" | sed "1i\\
===== fork $fork =====" >>"$attempt_dir/jfr-hot-methods.txt"
  "$jfr_bin" view --width 200 allocation-by-site "$recording" | sed "1i\\
===== fork $fork =====" >>"$attempt_dir/jfr-allocation-by-site.txt"
  "$jfr_bin" view --width 200 gc "$recording" | sed "1i\\
===== fork $fork =====" >>"$attempt_dir/jfr-gc.txt"
  "$jfr_bin" view --width 200 safepoints "$recording" | sed "1i\\
===== fork $fork =====" >>"$attempt_dir/jfr-safepoints.txt"

  perpetual=$(grep 'perpetualEndToEndBenchmark=PASS' "$fork_log" | tail -1)
  value() { printf '%s\n' "$perpetual" | tr ' ' '\n' | awk -F= -v key="$1" '$1==key {print $2; exit}'; }
  rates+=("$(value finalizedPerSec)")
  p50s+=("$(value p50Micros)")
  p99s+=("$(value p99Micros)")
  p999s+=("$(value p999Micros)")
  orderss+=("$(value orders)")
  matched_quantitys+=("$(value matchedQuantity)")
  expected_orders=$((25 * (maker_depth + 1)))
  expected_matched_quantity=$((25 * maker_depth))
  [[ "$(value orders)" == "$expected_orders" ]] || {
    echo "benchmark fork $fork finalized order count is not $expected_orders; see $fork_log" >&2
    exit 1
  }
  [[ "$(value matchedQuantity)" == "$expected_matched_quantity" ]] || {
    echo "benchmark fork $fork matched quantity is not $expected_matched_quantity; see $fork_log" >&2
    exit 1
  }
  [[ "$(value pendingMatching)" == "0" ]] || {
    echo "benchmark fork $fork has pending matching work; see $fork_log" >&2
    exit 1
  }
  concurrent=$(grep 'coreAcceptFreezeConcurrentBenchmark=PASS' "$fork_log" | tail -1)
  queue_depths+=("$(printf '%s\n' "$concurrent" | tr ' ' '\n' | awk -F= '$1=="maxQueueDepth" {print $2; exit}')")
  pending_depths+=("$(printf '%s\n' "$concurrent" | tr ' ' '\n' | awk -F= '$1=="pendingMatching" {print $2; exit}')")
done

for artifact in jfr-summary.txt jfr-hot-methods.txt jfr-allocation-by-site.txt jfr-gc.txt jfr-safepoints.txt; do
  [[ -s "$attempt_dir/$artifact" ]] || { echo "required JFR artifact is empty: $artifact" >&2; exit 1; }
done

median() { printf '%s\n' "$@" | sort -n | awk '{v[NR]=$1} END {print v[int((NR+1)/2)]}'; }
finalized_per_second=$(median "${rates[@]}")
p50=$(median "${p50s[@]}")
p99=$(median "${p99s[@]}")
p999=$(median "${p999s[@]}")
orders=$(median "${orderss[@]}")
matched_quantity=$(median "${matched_quantitys[@]}")
queue_max=$(printf '%s\n' "${queue_depths[@]}" | sort -nr | head -1)
pending_max=$(printf '%s\n' "${pending_depths[@]}" | sort -nr | head -1)
created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)

jq -n \
  --arg stage "$stage" --arg createdUtc "$created_utc" --arg suite "$benchmark_suite" \
  --arg javaHome "$selected_java_home" --arg javaBuild "$java_build" --arg javaVendor "$java_vendor" \
  --arg vmName "$vm_name" --arg runtimeVersion "$runtime_version" --arg javaFlags "$java_flags" \
  --arg hotMethods "$attempt_dir/jfr-hot-methods.txt" \
  --arg allocations "$attempt_dir/jfr-allocation-by-site.txt" \
  --arg gc "$attempt_dir/jfr-gc.txt" --arg safepoints "$attempt_dir/jfr-safepoints.txt" \
  --argjson forks "$forks" --argjson finalizedPerSecond "$finalized_per_second" \
  --arg jfrSettings "$jfr_settings" --argjson p50 "$p50" --argjson p99 "$p99" --argjson p999 "$p999" \
  --argjson queueMax "$queue_max" --argjson pendingMax "$pending_max" \
  --argjson makerDepth "$maker_depth" --argjson orders "$orders" --argjson matchedQuantity "$matched_quantity" \
  '{schemaVersion:1,result:"PASS",stage:$stage,createdUtc:$createdUtc,benchmarkSuite:$suite,forks:$forks,
    workload:{seedBase:9901,makerDepth:$makerDepth,perFork:{adapterOnlyOrders:500,acceptFreezeOrders:25,inMemoryOrders:25,
      concurrentIngressOrders:50,perpetualFinalizedOrders:$orders,perpetualMatchedQuantity:$matchedQuantity}},
    java:{home:$javaHome,build:$javaBuild,vendor:$javaVendor,vmName:$vmName,runtimeVersion:$runtimeVersion,
      flags:$javaFlags,jfrSettings:$jfrSettings},
    metrics:{offered:$orders,accepted:$orders,finalized:$orders,matchedQuantity:$matchedQuantity,perpetualPendingMatching:0,
      finalizedPerSecond:$finalizedPerSecond,
      pendingMax:$pendingMax,completionQueueMax:$queueMax,outboxMax:null},
    latency:{kind:"acceptance-to-finalization",coordinatedOmissionCorrected:true,
      p50Micros:$p50,p99Micros:$p99,p999Micros:$p999},
    invariants:{fundsDelta:null,stateHash:"not-exposed-by-local-baseline",bookEmpty:"not-queried"},
    jfr:{summary:"jfr-summary.txt",hotMethods:$hotMethods,allocationBySite:$allocations,gc:$gc,safepoints:$safepoints}}' \
  >"$attempt_dir/$stage-result.json.tmp"
jq -e '.result=="PASS" and .metrics.finalizedPerSecond and .latency.p999Micros and .jfr.hotMethods' \
  "$attempt_dir/$stage-result.json.tmp" >/dev/null
mv "$attempt_dir/$stage-result.json.tmp" "$attempt_dir/$stage-result.json"
echo "stageResult=PASS stage=$stage forks=$forks result=$attempt_dir/$stage-result.json"
