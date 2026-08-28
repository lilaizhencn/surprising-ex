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
JVM_ARGS=(
  "-Xms${HEAP}"
  "-Xmx${HEAP}"
  "-XX:SoftMaxHeapSize=${HEAP}"
  "-XX:+UseZGC"
  "-XX:+AlwaysPreTouch"
  "-XX:+DisableExplicitGC"
  "-XX:NativeMemoryTracking=summary"
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
JVM_ARGS_STRING="${JVM_ARGS[*]}"

write_environment_evidence() {
  mkdir -p "${ARTIFACT_DIR}"
  printf '%s\n' "${JAVA_VERSION}" > "${ARTIFACT_DIR}/java-version.txt"
  printf '%s\n' "${JVM_ARGS_STRING}" > "${ARTIFACT_DIR}/jvm-args.txt"
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

run_jmh() {
  "${JAVA}" -jar "${BENCHMARK_JAR}" \
    'LinearPerpetualCoreBenchmark.productionMixedWorkload' \
    -p accountLanes=4 -p activeUsers=1000,10000 -p symbols=4 \
    -p hftRounds=96 -p hftBatchSize=20 \
    -wi 2 -w 2s -i 3 -r 3s -f 1 -prof gc \
    -jvmArgsAppend "${JVM_ARGS_STRING}" \
    -rf json -rff "${ARTIFACT_DIR}/linear-perpetual-jmh.json" \
    | tee "${ARTIFACT_DIR}/linear-perpetual-jmh.log"
}

run_profile() {
  local profile_args="${JVM_ARGS_STRING} -XX:StartFlightRecording=filename=${ARTIFACT_DIR}/linear-perpetual.jfr,settings=profile,dumponexit=true -Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=20m"
  "${JAVA}" -jar "${BENCHMARK_JAR}" \
    'LinearPerpetualCoreBenchmark.productionMixedWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p symbols=4 \
    -p hftRounds=96 -p hftBatchSize=20 \
    -wi 2 -w 2s -i 1 -r 8s -f 1 \
    -jvmArgsAppend "${profile_args}" \
    -rf json -rff "${ARTIFACT_DIR}/linear-perpetual-profile.json" \
    | tee "${ARTIFACT_DIR}/linear-perpetual-profile.log"
}

case "${MODE}" in
  test)
    run_tests
    ;;
  jmh)
    write_environment_evidence
    package_benchmarks
    run_jmh
    ;;
  profile)
    write_environment_evidence
    package_benchmarks
    run_profile
    ;;
  all)
    run_tests
    package_benchmarks
    run_jmh
    run_profile
    ;;
  *)
    echo "Usage: $0 [test|jmh|profile|all]" >&2
    exit 2
    ;;
esac

echo "Qualification artifacts: ${ARTIFACT_DIR}"
