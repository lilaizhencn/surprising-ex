#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
MODULE="surprising-aeron-core/surprising-aeron-benchmarks"
JAR="${REPO_ROOT}/${MODULE}/target/product-core-benchmarks.jar"
RUN_ID="${QUALIFICATION_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ARTIFACT_DIR="${QUALIFICATION_ARTIFACT_DIR:-${REPO_ROOT}/target/qualification/${RUN_ID}-scale}"
MODE="${1:-all}"
JAVA_HOME_25="${SURPRISING_JAVA_HOME:-/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home}"
JAVA="${JAVA_HOME_25}/bin/java"
MAVEN="${MAVEN:-mvn}"
HEAP="${QUALIFICATION_HEAP:-8g}"
PROBE_WARMUP="${PROBE_WARMUP_CYCLES:-1}"
PROBE_MEASURE="${PROBE_MEASUREMENT_CYCLES:-3}"
JMH_WARMUP_ITERATIONS="${SCALE_JMH_WARMUP_ITERATIONS:-2}"
JMH_WARMUP_SECONDS="${SCALE_JMH_WARMUP_SECONDS:-2}"
JMH_MEASUREMENT_ITERATIONS="${SCALE_JMH_MEASUREMENT_ITERATIONS:-3}"
JMH_MEASUREMENT_SECONDS="${SCALE_JMH_MEASUREMENT_SECONDS:-3}"
JMH_FORKS="${SCALE_JMH_FORKS:-3}"
RESUME="${QUALIFICATION_RESUME:-false}"
LIFECYCLE_SYMBOL_BUDGET="${SCALE_LIFECYCLE_SYMBOL_BUDGET:-32}"
SOAK_SECONDS="${SCALE_SOAK_SECONDS:-2400}"
SOAK_SAMPLE_SECONDS="${SCALE_SOAK_SAMPLE_SECONDS:-10}"
SATURATION_OPERATIONS="${SATURATION_OPERATIONS_PER_INVOCATION:-16384}"

JAVA_VERSION="$(${JAVA} -version 2>&1)"
if [[ "${JAVA_VERSION}" != *'java version "25.'* || "${JAVA_VERSION}" != *'HotSpot'* ]]; then
  echo "Scale qualification requires HotSpot JDK 25; found:" >&2
  echo "${JAVA_VERSION}" >&2
  exit 2
fi

JVM_ARGS=(
  "-Xms${HEAP}"
  "-Xmx${HEAP}"
  "-XX:SoftMaxHeapSize=${HEAP}"
  "-XX:+UseZGC"
  "-XX:+AlwaysPreTouch"
  "-XX:+DisableExplicitGC"
  "--enable-native-access=ALL-UNNAMED"
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
  "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
  "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
  "-Dsurprising.aeron.account-lanes=4"
  "-Dsurprising.aeron.matching-engines=4"
  "-Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN"
  "-Dsurprising.aeron.settlement-wait-strategy=BLOCKING"
  "-Dsurprising.aeron.matching-completion-spins=16384"
  "-Dsurprising.aeron.projection-busy-spin=false"
  "-Dsurprising.aeron.projection-batch-size=64"
)
JVM_ARGS_STRING="${JVM_ARGS[*]}"

write_environment() {
  mkdir -p "${ARTIFACT_DIR}"
  printf '%s\n' "${JAVA_VERSION}" > "${ARTIFACT_DIR}/java-version.txt"
  printf '%s\n' "${JVM_ARGS_STRING}" > "${ARTIFACT_DIR}/jvm-args.txt"
}

package_benchmark() {
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl "${MODULE}" -am -DskipTests clean package | tee "${ARTIFACT_DIR}/maven-package.log"
  jar tf "${JAR}" | grep -qx 'META-INF/BenchmarkList'
  jar tf "${JAR}" | grep -qx 'META-INF/CompilerHints'
}

run_probe_case() {
  local case_id="$1"
  shift
  if [[ "${RESUME}" == "true" && -s "${ARTIFACT_DIR}/${case_id}.log" ]] \
      && tail -n 1 "${ARTIFACT_DIR}/${case_id}.log" | jq -e \
        '.status == "PASS" and .fundsInvariant == true and .terminalBusinessOperations > 0' > /dev/null; then
    tail -n 1 "${ARTIFACT_DIR}/${case_id}.log" >> "${ARTIFACT_DIR}/scale-matrix.jsonl"
    return
  fi
  "${JAVA}" "${JVM_ARGS[@]}" -cp "${JAR}" \
    com.surprising.aeron.service.LinearPerpetualScaleProbeMain \
    "$@" "${PROBE_WARMUP}" "${PROBE_MEASURE}" \
    2> "${ARTIFACT_DIR}/${case_id}.stderr.log" | tee "${ARTIFACT_DIR}/${case_id}.log"
  tail -n 1 "${ARTIFACT_DIR}/${case_id}.log" | jq -e \
    '.status == "PASS" and .fundsInvariant == true and .terminalBusinessOperations > 0' \
    > /dev/null
  tail -n 1 "${ARTIFACT_DIR}/${case_id}.log" >> "${ARTIFACT_DIR}/scale-matrix.jsonl"
}

run_probe_matrix() {
  : > "${ARTIFACT_DIR}/scale-matrix.jsonl"
  run_probe_case symbols-u10000-s512 \
    10000 512 512 1 3 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_probe_case density-u10000-s512-p5-o10 \
    10000 512 512 5 10 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_probe_case traffic-u10000-s512-pareto \
    10000 512 512 1 3 PARETO_80_20 5 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_probe_case traffic-u10000-s512-hot \
    10000 512 512 1 3 SINGLE_HOT 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_probe_case traffic-u10000-s512-storm \
    10000 512 512 1 3 MARK_PRICE_STORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_probe_case full-sweep-u10000-s512 \
    10000 512 512 1 3 UNIFORM 1 4 512
  jq -s '.' "${ARTIFACT_DIR}/scale-matrix.jsonl" > "${ARTIFACT_DIR}/scale-matrix.json"
}

validate_jmh() {
  local result="$1"
  jq -e 'length == 1 and all(.[ ];
    .secondaryMetrics.acceptedBusinessOperations.score > 0 and
    .secondaryMetrics.acceptedBusinessOperations.score ==
      .secondaryMetrics.terminalBusinessOperations.score and
    .secondaryMetrics.acceptedCoreMessages.score == .secondaryMetrics.terminalCoreMessages.score and
    .secondaryMetrics.unfinishedBusinessOperations.score == 0 and
    .secondaryMetrics.unfinishedCoreMessages.score == 0)' "${result}" > /dev/null
}

run_jmh_case() {
  local case_id="$1" users="$2" listed="$3" active="$4" positions="$5" orders="$6" profile="$7" rounds="$8"
  local lifecycle_budget="${9:-${LIFECYCLE_SYMBOL_BUDGET}}"
  local result="${ARTIFACT_DIR}/jmh-${case_id}.json"
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.scaleMixedWorkload' \
    -p accountLanes=4 -p activeUsers="${users}" -p listedSymbols="${listed}" \
    -p activeSymbols="${active}" -p maxPositionsPerUser="${positions}" \
    -p maxOpenOrdersPerUser="${orders}" -p trafficProfile="${profile}" \
    -p hftRounds="${rounds}" -p hftBatchSize=4 \
    -p lifecycleSymbolsPerRun="${lifecycle_budget}" \
    -wi "${JMH_WARMUP_ITERATIONS}" -w "${JMH_WARMUP_SECONDS}s" \
    -i "${JMH_MEASUREMENT_ITERATIONS}" -r "${JMH_MEASUREMENT_SECONDS}s" -f "${JMH_FORKS}" \
    -jvmArgsAppend "${JVM_ARGS_STRING}" -rf json -rff "${result}" \
    2>&1 | tee "${ARTIFACT_DIR}/jmh-${case_id}.log"
  validate_jmh "${result}"
}

run_jmh_matrix() {
  run_jmh_case uniform-512 10000 512 512 1 3 UNIFORM 1
  run_jmh_case pareto-512 10000 512 512 1 3 PARETO_80_20 5
  run_jmh_case storm-512 10000 512 512 1 3 MARK_PRICE_STORM 1
  run_jmh_case density-512 10000 512 512 5 10 UNIFORM 1
  run_jmh_case full-sweep-512 10000 512 512 1 3 UNIFORM 1 512
  jq -s 'add' "${ARTIFACT_DIR}"/jmh-*.json > "${ARTIFACT_DIR}/scale-jmh.json"
}

run_gc() {
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.scaleMixedWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 -p trafficProfile=UNIFORM \
    -p hftRounds=1 -p hftBatchSize=4 -p lifecycleSymbolsPerRun="${LIFECYCLE_SYMBOL_BUDGET}" \
    -wi 2 -w 2s -i 3 -r 3s -f 1 -prof gc \
    -jvmArgsAppend "${JVM_ARGS_STRING}" -rf json -rff "${ARTIFACT_DIR}/scale-gc.json" \
    2>&1 | tee "${ARTIFACT_DIR}/scale-gc.log"
  validate_jmh "${ARTIFACT_DIR}/scale-gc.json"
  jq -e '.[0].secondaryMetrics["gc.alloc.rate"].score > 0' \
    "${ARTIFACT_DIR}/scale-gc.json" > /dev/null
}

run_profile() {
  local profile_args="${JVM_ARGS_STRING} -XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics -XX:StartFlightRecording=filename=${ARTIFACT_DIR}/scale.jfr,settings=profile,dumponexit=true -Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/scale-profile-gc.log:time,uptime,level,tags"
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.scaleMixedWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 -p trafficProfile=UNIFORM \
    -p hftRounds=1 -p hftBatchSize=4 -p lifecycleSymbolsPerRun="${LIFECYCLE_SYMBOL_BUDGET}" \
    -wi 2 -w 2s -i 1 -r 10s -f 1 \
    -jvmArgsAppend "${profile_args}" -rf json -rff "${ARTIFACT_DIR}/scale-profile.json" \
    2>&1 | tee "${ARTIFACT_DIR}/scale-profile.log"
  validate_jmh "${ARTIFACT_DIR}/scale-profile.json"
  [[ -s "${ARTIFACT_DIR}/scale.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/scale-profile-gc.log" ]]
  grep -q 'Native Memory Tracking:' "${ARTIFACT_DIR}/scale-profile.log"
}

run_soak() {
  local soak_jvm_args=(
    "${JVM_ARGS[@]}"
    "-Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/scale-soak-gc.log:time,uptime,level,tags"
  )
  "${JAVA}" "${soak_jvm_args[@]}" -cp "${JAR}" \
    com.surprising.aeron.service.LinearPerpetualScaleSoakMain \
    10000 512 512 5 10 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}" \
    "${SOAK_SECONDS}" "${SOAK_SAMPLE_SECONDS}" \
    2> "${ARTIFACT_DIR}/scale-soak.stderr.log" | tee "${ARTIFACT_DIR}/scale-soak.jsonl"
  tail -n 1 "${ARTIFACT_DIR}/scale-soak.jsonl" | jq -e \
    '.type == "summary" and .status == "PASS" and .fundsInvariant == true
      and .terminalBusinessOperations > 0 and .listedSymbols == 512' \
    > "${ARTIFACT_DIR}/scale-soak.json"
  [[ -s "${ARTIFACT_DIR}/scale-soak-gc.log" ]]
}

run_capacity() {
  : > "${ARTIFACT_DIR}/scale-matrix.jsonl"
  run_probe_case density-extreme-u10000-s512-p20-o100 \
    10000 512 512 20 100 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_jmh_case density-extreme-512 10000 512 512 20 100 UNIFORM 1
}

run_saturation_case() {
  local strategy="$1" in_flight="$2"
  local case_id
  case "${strategy}" in
    BUSY_SPIN) case_id="busy-spin-inflight-${in_flight}" ;;
    YIELDING) case_id="yielding-inflight-${in_flight}" ;;
    *) echo "Unsupported saturation wait strategy: ${strategy}" >&2; return 2 ;;
  esac
  local result="${ARTIFACT_DIR}/saturation-${case_id}.json"
  local saturation_args="${JVM_ARGS_STRING} -Dsurprising.aeron.matcher-wait-strategy=${strategy} -Dsurprising.benchmark.export-ack-interval=1024"
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.saturatedMatchingWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 \
    -p maxInFlight="${in_flight}" -p operationsPerInvocation="${SATURATION_OPERATIONS}" \
    -wi 1 -w 3s -i 3 -r 5s -f 1 -t 1 \
    -jvmArgsAppend "${saturation_args}" -rf json -rff "${result}" \
    2>&1 | tee "${ARTIFACT_DIR}/saturation-${case_id}.log"
  validate_jmh "${result}"
}

run_saturation_profile() {
  local profile_args="${JVM_ARGS_STRING} -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.benchmark.export-ack-interval=1024 -XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics -XX:StartFlightRecording=filename=${ARTIFACT_DIR}/saturation.jfr,settings=profile,dumponexit=true -Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/saturation-gc.log:time,uptime,level,tags"
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.saturatedMatchingWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 \
    -p maxInFlight=256 -p operationsPerInvocation="${SATURATION_OPERATIONS}" \
    -wi 1 -w 3s -i 1 -r 10s -f 1 -t 1 \
    -jvmArgsAppend "${profile_args}" -rf json -rff "${ARTIFACT_DIR}/saturation-profile.json" \
    2>&1 | tee "${ARTIFACT_DIR}/saturation-profile.log"
  validate_jmh "${ARTIFACT_DIR}/saturation-profile.json"
  [[ -s "${ARTIFACT_DIR}/saturation.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/saturation-gc.log" ]]
  grep -q 'Native Memory Tracking:' "${ARTIFACT_DIR}/saturation-profile.log"
}

run_saturation() {
  run_saturation_case BUSY_SPIN 64
  run_saturation_case BUSY_SPIN 256
  run_saturation_case BUSY_SPIN 1024
  run_saturation_case YIELDING 256
  run_saturation_profile
  jq -s 'add' "${ARTIFACT_DIR}"/saturation-*.json > "${ARTIFACT_DIR}/saturation-matrix.json"
}

write_environment
case "${MODE}" in
  probe) package_benchmark; run_probe_matrix ;;
  jmh) package_benchmark; run_jmh_matrix ;;
  gc) package_benchmark; run_gc ;;
  profile) package_benchmark; run_profile ;;
  soak) package_benchmark; run_soak ;;
  capacity) package_benchmark; run_capacity ;;
  saturation) package_benchmark; run_saturation ;;
  all) package_benchmark; run_probe_matrix; run_jmh_matrix; run_gc; run_profile; run_soak ;;
  *) echo "Usage: $0 [probe|jmh|gc|profile|soak|capacity|saturation|all]" >&2; exit 2 ;;
esac
echo "Scale qualification artifacts: ${ARTIFACT_DIR}"
