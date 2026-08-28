#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
MODULE="surprising-aeron-core/surprising-aeron-benchmarks"
BENCHMARK_JAR="${REPO_ROOT}/${MODULE}/target/product-core-benchmarks.jar"
RUN_ID="${QUALIFICATION_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ARTIFACT_DIR="${QUALIFICATION_ARTIFACT_DIR:-${REPO_ROOT}/${MODULE}/target/qualification/${RUN_ID}}"
MODE="${1:-all}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -r "${TEMP_DIR}"' EXIT

DEFAULT_JAVA_HOME="/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home"
if [[ -n "${SURPRISING_JAVA_HOME:-}" ]]; then
  QUALIFICATION_JAVA_HOME="${SURPRISING_JAVA_HOME}"
elif [[ -d "${DEFAULT_JAVA_HOME}" ]]; then
  QUALIFICATION_JAVA_HOME="${DEFAULT_JAVA_HOME}"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  QUALIFICATION_JAVA_HOME="${JAVA_HOME}"
else
  echo "Set SURPRISING_JAVA_HOME to an Oracle GraalVM HotSpot JDK 25 installation." >&2
  exit 2
fi

JAVA="${QUALIFICATION_JAVA_HOME}/bin/java"
MAVEN="${MAVEN:-mvn}"
JAVA_VERSION="$(${JAVA} -version 2>&1)"
if [[ "${JAVA_VERSION}" != *'java version "25.'* || "${JAVA_VERSION}" != *'HotSpot'* ]]; then
  echo "Qualification requires HotSpot JDK 25; found:" >&2
  echo "${JAVA_VERSION}" >&2
  exit 2
fi

HEAP="${QUALIFICATION_HEAP:-4g}"
MATCHER_WAIT_STRATEGY="${MATCHER_WAIT_STRATEGY:-BUSY_SPIN}"
PROJECTION_BUSY_SPIN="${PROJECTION_BUSY_SPIN:-false}"
MATCHING_COMPLETION_SPINS="${MATCHING_COMPLETION_SPINS:-16384}"
THROUGHPUT_WARMUP_ITERATIONS="${THROUGHPUT_WARMUP_ITERATIONS:-5}"
THROUGHPUT_WARMUP_SECONDS="${THROUGHPUT_WARMUP_SECONDS:-5}"
THROUGHPUT_MEASUREMENT_ITERATIONS="${THROUGHPUT_MEASUREMENT_ITERATIONS:-5}"
THROUGHPUT_MEASUREMENT_SECONDS="${THROUGHPUT_MEASUREMENT_SECONDS:-5}"
THROUGHPUT_FORKS="${THROUGHPUT_FORKS:-3}"
ATTRIBUTION_WARMUP_ITERATIONS="${ATTRIBUTION_WARMUP_ITERATIONS:-3}"
ATTRIBUTION_WARMUP_SECONDS="${ATTRIBUTION_WARMUP_SECONDS:-3}"
ATTRIBUTION_MEASUREMENT_ITERATIONS="${ATTRIBUTION_MEASUREMENT_ITERATIONS:-3}"
ATTRIBUTION_MEASUREMENT_SECONDS="${ATTRIBUTION_MEASUREMENT_SECONDS:-3}"
PROFILE_WARMUP_ITERATIONS="${PROFILE_WARMUP_ITERATIONS:-5}"
PROFILE_WARMUP_SECONDS="${PROFILE_WARMUP_SECONDS:-5}"
PROFILE_MEASUREMENT_SECONDS="${PROFILE_MEASUREMENT_SECONDS:-15}"
E2E_CYCLES="${E2E_CYCLES:-10000}"
E2E_MAKER_DEPTH="${E2E_MAKER_DEPTH:-1}"
MAIN_JVM_ARGS=(
  "-Xms${HEAP}"
  "-Xmx${HEAP}"
  "-XX:SoftMaxHeapSize=${HEAP}"
  "-XX:+UseZGC"
  "-XX:+AlwaysPreTouch"
  "-XX:+DisableExplicitGC"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=${ARTIFACT_DIR}"
  "--enable-native-access=ALL-UNNAMED"
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
  "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
  "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
  "-Dsurprising.aeron.matching-engines=4"
  "-Dsurprising.aeron.matcher-wait-strategy=${MATCHER_WAIT_STRATEGY}"
  "-Dsurprising.aeron.matching-completion-spins=${MATCHING_COMPLETION_SPINS}"
  "-Dsurprising.aeron.projection-busy-spin=${PROJECTION_BUSY_SPIN}"
  "-Dsurprising.aeron.projection-batch-size=64"
)
MAIN_JVM_ARGS_STRING="${MAIN_JVM_ARGS[*]}"
PROFILE_JVM_ARGS_STRING="${MAIN_JVM_ARGS_STRING} -XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics"

validate_business_results() {
  local result_file="$1"
  local expected_results="$2"
  jq -e --argjson expected_results "${expected_results}" '
    length == $expected_results and
    all(.[ ];
      .secondaryMetrics.acceptedBusinessOperations.score > 0 and
      .secondaryMetrics.terminalBusinessOperations.score > 0 and
      ((.secondaryMetrics.acceptedBusinessOperations.score
        - .secondaryMetrics.terminalBusinessOperations.score) | fabs) < 0.000001 and
      .secondaryMetrics.acceptedCoreMessages.score > 0 and
      .secondaryMetrics.terminalCoreMessages.score > 0 and
      ((.secondaryMetrics.acceptedCoreMessages.score
        - .secondaryMetrics.terminalCoreMessages.score) | fabs) < 0.000001 and
      .secondaryMetrics.unfinishedBusinessOperations.score == 0 and
      .secondaryMetrics.unfinishedCoreMessages.score == 0)
  ' "${result_file}" > /dev/null || {
    echo "Qualification result validation failed: ${result_file}" >&2
    return 1
  }
}

write_environment_evidence() {
  mkdir -p "${ARTIFACT_DIR}"
  printf '%s\n' "${JAVA_VERSION}" > "${ARTIFACT_DIR}/java-version.txt"
  printf 'throughput=%s\nprofile=%s\n' \
    "${MAIN_JVM_ARGS_STRING}" "${PROFILE_JVM_ARGS_STRING}" > "${ARTIFACT_DIR}/jvm-args.txt"
}

run_tests() {
  JAVA_HOME="${QUALIFICATION_JAVA_HOME}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl "${MODULE}" -am clean test | tee "${TEMP_DIR}/maven-test.log"
  write_environment_evidence
  cp "${TEMP_DIR}/maven-test.log" "${ARTIFACT_DIR}/maven-test.log"
}

package_benchmarks() {
  JAVA_HOME="${QUALIFICATION_JAVA_HOME}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl "${MODULE}" -am -DskipTests package | tee "${ARTIFACT_DIR}/maven-package.log"
}

run_throughput() {
  "${JAVA}" -jar "${BENCHMARK_JAR}" \
    'LinearPerpetualCoreBenchmark.productionMixedWorkload' \
    -p accountLanes=4 -p activeUsers=1000,10000 -p symbols=4 \
    -p hftRounds=96 -p hftBatchSize=20 \
    -wi "${THROUGHPUT_WARMUP_ITERATIONS}" -w "${THROUGHPUT_WARMUP_SECONDS}s" \
    -i "${THROUGHPUT_MEASUREMENT_ITERATIONS}" -r "${THROUGHPUT_MEASUREMENT_SECONDS}s" \
    -f "${THROUGHPUT_FORKS}" \
    -jvmArgsAppend "${MAIN_JVM_ARGS_STRING}" \
    -rf json -rff "${ARTIFACT_DIR}/linear-perpetual-throughput.json" \
    2>&1 | tee "${ARTIFACT_DIR}/linear-perpetual-throughput.log"
  validate_business_results "${ARTIFACT_DIR}/linear-perpetual-throughput.json" 2
  jq -e '
    ([.[].params.activeUsers] | sort) == ["1000", "10000"] and
    all(.[ ];
      .secondaryMetrics["gc.alloc.rate"] == null and
      (.jvmArgs | index("-XX:NativeMemoryTracking=summary")) == null)
  ' "${ARTIFACT_DIR}/linear-perpetual-throughput.json" > /dev/null
}

run_gc_attribution() {
  "${JAVA}" -jar "${BENCHMARK_JAR}" \
    'LinearPerpetualCoreBenchmark.productionMixedWorkload' \
    -p accountLanes=4 -p activeUsers=1000,10000 -p symbols=4 \
    -p hftRounds=96 -p hftBatchSize=20 \
    -wi "${ATTRIBUTION_WARMUP_ITERATIONS}" -w "${ATTRIBUTION_WARMUP_SECONDS}s" \
    -i "${ATTRIBUTION_MEASUREMENT_ITERATIONS}" -r "${ATTRIBUTION_MEASUREMENT_SECONDS}s" \
    -f 1 -prof gc \
    -jvmArgsAppend "${MAIN_JVM_ARGS_STRING}" \
    -rf json -rff "${ARTIFACT_DIR}/linear-perpetual-gc.json" \
    2>&1 | tee "${ARTIFACT_DIR}/linear-perpetual-gc.log"
  validate_business_results "${ARTIFACT_DIR}/linear-perpetual-gc.json" 2
  jq -e '
    ([.[].params.activeUsers] | sort) == ["1000", "10000"] and
    all(.[ ]; .secondaryMetrics["gc.alloc.rate"].score > 0)
  ' "${ARTIFACT_DIR}/linear-perpetual-gc.json" > /dev/null
}

run_profile() {
  local profile_args="${PROFILE_JVM_ARGS_STRING} -XX:StartFlightRecording=filename=${ARTIFACT_DIR}/linear-perpetual.jfr,settings=profile,dumponexit=true -Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=20m"
  "${JAVA}" -jar "${BENCHMARK_JAR}" \
    'LinearPerpetualCoreBenchmark.productionMixedWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p symbols=4 \
    -p hftRounds=96 -p hftBatchSize=20 \
    -wi "${PROFILE_WARMUP_ITERATIONS}" -w "${PROFILE_WARMUP_SECONDS}s" \
    -i 1 -r "${PROFILE_MEASUREMENT_SECONDS}s" -f 1 \
    -jvmArgsAppend "${profile_args}" \
    -rf json -rff "${ARTIFACT_DIR}/linear-perpetual-profile.json" \
    2>&1 | tee "${ARTIFACT_DIR}/linear-perpetual-profile.log"
  validate_business_results "${ARTIFACT_DIR}/linear-perpetual-profile.json" 1
  jq -e '
    .[0].params.activeUsers == "10000" and
    (.[0].jvmArgs | index("-XX:NativeMemoryTracking=summary")) != null
  ' "${ARTIFACT_DIR}/linear-perpetual-profile.json" > /dev/null
  [[ -s "${ARTIFACT_DIR}/linear-perpetual.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/gc.log" ]]
  grep -q 'Native Memory Tracking:' "${ARTIFACT_DIR}/linear-perpetual-profile.log"
}

run_e2e() {
  "${JAVA}" "${MAIN_JVM_ARGS[@]}" -cp "${BENCHMARK_JAR}" \
    com.surprising.aeron.service.CorePerpetualEndToEndBenchmark \
    "${E2E_CYCLES}" 100 "${E2E_MAKER_DEPTH}" \
    2>&1 | tee "${ARTIFACT_DIR}/e2e.log"
  grep -q 'perpetualEndToEndBenchmark=PASS' "${ARTIFACT_DIR}/e2e.log"
  grep -q 'pendingMatching=0' "${ARTIFACT_DIR}/e2e.log"
}

case "${MODE}" in
  test)
    run_tests
    ;;
  throughput|jmh)
    write_environment_evidence
    package_benchmarks
    run_throughput
    ;;
  gc)
    write_environment_evidence
    package_benchmarks
    run_gc_attribution
    ;;
  profile)
    write_environment_evidence
    package_benchmarks
    run_profile
    ;;
  e2e)
    write_environment_evidence
    package_benchmarks
    run_e2e
    ;;
  all)
    run_tests
    package_benchmarks
    run_throughput
    run_gc_attribution
    run_profile
    run_e2e
    ;;
  *)
    echo "Usage: $0 [test|throughput|jmh|gc|profile|e2e|all]" >&2
    exit 2
    ;;
esac

echo "Qualification artifacts: ${ARTIFACT_DIR}"
