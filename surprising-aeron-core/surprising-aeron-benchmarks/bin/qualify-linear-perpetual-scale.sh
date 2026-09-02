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
SOAK_OLD_OBJECT_PATHS="${SCALE_SOAK_OLD_OBJECT_PATHS:-false}"
SATURATION_OPERATIONS="${SATURATION_OPERATIONS_PER_INVOCATION:-16384}"
MATCHING_ENGINES="${MATCHING_ENGINES:-1}"
JFR_ANALYZER="${SCRIPT_DIR}/analyze-owner-commit-jfr.sh"
SUREFIRE_VERIFIER="${SCRIPT_DIR}/verify-surefire-reports.sh"
OLD_OBJECT_JFC_GENERATOR="${SCRIPT_DIR}/generate-oldobject-jfc.sh"
JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE:-${REPO_ROOT}/${MODULE}/config/owner-commit-profile.jfc}"
if [[ ! -s "${JFR_SETTINGS_FILE}" ]]; then
  echo "missing explicit JFR settings: ${JFR_SETTINGS_FILE}" >&2
  exit 2
fi
if (( MATCHING_ENGINES != 1 )); then
  echo "MATCHING_ENGINES must be exactly 1; found ${MATCHING_ENGINES}." >&2
  exit 2
fi

JAVA_VERSION="$(${JAVA} -version 2>&1)"
if [[ ( "${JAVA_VERSION}" != *'version "25"'* && "${JAVA_VERSION}" != *'version "25.'* ) \
    || "${JAVA_VERSION}" == *'OpenJ9'* ]] \
    || [[ "${JAVA_VERSION}" != *'HotSpot'* && "${JAVA_VERSION}" != *'OpenJDK 64-Bit Server VM'* ]]; then
  echo "Scale qualification requires HotSpot JDK 25; found:" >&2
  echo "${JAVA_VERSION}" >&2
  exit 2
fi
MAVEN_VERSION="$(JAVA_HOME="${JAVA_HOME_25}" PATH="${JAVA_HOME_25}/bin:${PATH}" "${MAVEN}" -version 2>&1)"
if [[ ( "${MAVEN_VERSION}" != *'Java version: 25,'* \
    && "${MAVEN_VERSION}" != *'Java version: 25.'* ) || "${MAVEN_VERSION}" == *'OpenJ9'* ]]; then
  echo "Scale qualification requires Maven to run on HotSpot-compatible JDK 25; found:" >&2
  echo "${MAVEN_VERSION}" >&2
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
  "-Dsurprising.aeron.matching-engines=${MATCHING_ENGINES}"
  "-Dsurprising.aeron.settlement-wait-strategy=BLOCKING"
  "-Dsurprising.aeron.commit-journal-capacity=65536"
  "-Dsurprising.aeron.commit-journal-capacity-bytes=1073741824"
  "-Dsurprising.aeron.export-pending-bytes=268435456"
)
JVM_ARGS_STRING="${JVM_ARGS[*]}"

write_environment() {
  mkdir -p "${ARTIFACT_DIR}"
  printf '%s\n' "${JAVA_VERSION}" > "${ARTIFACT_DIR}/java-version.txt"
  printf '%s\n' "${MAVEN_VERSION}" > "${ARTIFACT_DIR}/maven-version.txt"
  printf '%s\n' "${JVM_ARGS_STRING}" > "${ARTIFACT_DIR}/jvm-args.txt"
  cp "${JFR_SETTINGS_FILE}" "${ARTIFACT_DIR}/owner-commit-profile.jfc"
}

run_targeted_tests() {
  local protocol_tests='CoreExportCodecTest'
  local service_tests='RuntimeCommitPatchTest,RuntimeCommitHashTest,RuntimeCommitJournalTest,RuntimeCommitRecoveryTest,RuntimeStateProjectorTest,TradingRuntimeStateTest,TradingRuntimeStateIndexTest,RuntimeTreasuryDeltaTest,RuntimeCommandProcessorTest,CoreProbeStateTest,CoreOrderedOrderBatchTest,CoreLifecycleStateTest,CoreMatchingStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreTreasuryStateTest,FundsDeltaTest,RuntimePerpetualMatchProcessorTest,RuntimePerpetualFundingProcessorTest,RuntimePerpetualFillCalculatorTest,CoreRiskStateTest,CoreProductLineArchitectureContractTest,CoreFundsIdempotencyTest,CoreStateSnapshotCodecTest,TradingStateSnapshotCodecTest,CoreFeePolicySnapshotCodecTest,CoreNativeSnapshotProductLineTest,SharedProductLineSnapshotContractTest,TradingCoreRuntimeAuthorityTest,W1W2InvariantFenceTest,DeterministicExchangeCoreAdapterTest,SurprisingClusteredServiceTest,ActiveOrderIndexTest,PositionUserIndexTest,RiskSnapshotIndexTest'
  local exporter_tests='ReliableCoreExporterTest,KafkaProjectionWorkerTest,KafkaCoreExportSinkTest,JdbcCoreEventProjectorTest,JdbcCoreEventProjectorPostgresTest,AdaptiveExportLoopTest,W5PublishBarrierTest'
  local gateway_tests='CoreEventFanoutConsumerTest,KafkaFanoutConsumerTest,KafkaFanoutConsumerTopicTest'
  local market_data_tests='CoreMarketDataProjectionTest'
  local benchmark_tests='LinearPerpetualBenchmarkSupportTest'
  local protocol_marker="${ARTIFACT_DIR}/maven-protocol-tests.start"
  local service_marker="${ARTIFACT_DIR}/maven-service-tests.start"
  local exporter_marker="${ARTIFACT_DIR}/maven-exporter-tests.start"
  local gateway_marker="${ARTIFACT_DIR}/maven-gateway-tests.start"
  local market_data_marker="${ARTIFACT_DIR}/maven-market-data-tests.start"
  local benchmark_marker="${ARTIFACT_DIR}/maven-benchmark-tests.start"

  : > "${protocol_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-aeron-core/surprising-aeron-protocol -am \
    -Dtest="${protocol_tests}" -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-protocol-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/surprising-aeron-core/surprising-aeron-protocol/target/surefire-reports" \
    "${protocol_tests}" "${protocol_marker}" "${ARTIFACT_DIR}/surefire-protocol.tsv"

  : > "${service_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-aeron-core/surprising-aeron-service -am \
    -Dtest="${service_tests}" -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-service-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/surprising-aeron-core/surprising-aeron-service/target/surefire-reports" \
    "${service_tests}" "${service_marker}" "${ARTIFACT_DIR}/surefire-service.tsv"

  : > "${exporter_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-aeron-core/surprising-aeron-exporter -am \
    -Dtest="${exporter_tests}" \
    -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-exporter-consumer-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/surprising-aeron-core/surprising-aeron-exporter/target/surefire-reports" \
    "${exporter_tests}" "${exporter_marker}" "${ARTIFACT_DIR}/surefire-exporter.tsv"

  : > "${gateway_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-gateway -am \
    -Dtest="${gateway_tests}" \
    -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-gateway-consumer-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/surprising-gateway/target/surefire-reports" \
    "${gateway_tests}" "${gateway_marker}" "${ARTIFACT_DIR}/surefire-gateway.tsv"

  : > "${market_data_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-market-data/surprising-market-data-provider -am \
    -Dtest="${market_data_tests}" -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-market-data-consumer-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/surprising-market-data/surprising-market-data-provider/target/surefire-reports" \
    "${market_data_tests}" "${market_data_marker}" "${ARTIFACT_DIR}/surefire-market-data.tsv"
  : > "${benchmark_marker}"
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl "${MODULE}" -am -Dtest="${benchmark_tests}" \
    -Dsurefire.failIfNoSpecifiedTests=false test \
    | tee "${ARTIFACT_DIR}/maven-benchmark-tests.log"
  bash "${SUREFIRE_VERIFIER}" \
    "${REPO_ROOT}/${MODULE}/target/surefire-reports" \
    "${benchmark_tests}" "${benchmark_marker}" "${ARTIFACT_DIR}/surefire-benchmark.tsv"
}

package_benchmark() {
  JAVA_HOME="${JAVA_HOME_25}" "${MAVEN}" -f "${REPO_ROOT}/pom.xml" \
    -pl "${MODULE}" -am -DskipTests clean package | tee "${ARTIFACT_DIR}/maven-package.log"
  jar tf "${JAR}" | grep -qx 'META-INF/BenchmarkList'
  jar tf "${JAR}" | grep -qx 'META-INF/CompilerHints'
}

capture_nmt_baseline() {
  local process_pid="$1" prefix="$2"
  for _ in {1..100}; do
    kill -0 "${process_pid}" 2>/dev/null || break
    if "${JAVA_HOME_25}/bin/jcmd" "${process_pid}" \
        VM.native_memory baseline > "${prefix}-nmt-baseline.txt" 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  echo "failed to capture NMT baseline from process ${process_pid}" >&2
  return 1
}

capture_process_nmt() {
  local process_pid="$1" prefix="$2"
  capture_nmt_baseline "${process_pid}" "${prefix}"
  while kill -0 "${process_pid}" 2>/dev/null; do
    "${JAVA_HOME_25}/bin/jcmd" "${process_pid}" VM.native_memory summary.diff \
      > "${prefix}-nmt-summary.diff.tmp" 2>/dev/null || true
    if [[ -s "${prefix}-nmt-summary.diff.tmp" ]]; then
      mv "${prefix}-nmt-summary.diff.tmp" "${prefix}-nmt-summary.diff.txt"
    fi
    sleep 1
  done
  rm -f "${prefix}-nmt-summary.diff.tmp"
  [[ -s "${prefix}-nmt-summary.diff.txt" ]]
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
    .secondaryMetrics.unfinishedCoreMessages.score == 0 and
    .secondaryMetrics.rejectedBusinessOperations.score == 0 and
    .secondaryMetrics.errorBusinessOperations.score == 0 and
    .secondaryMetrics.timedOutBusinessOperations.score == 0)' "${result}" > /dev/null
}

validate_saturation_jmh() {
  local result="$1"
  validate_jmh "${result}"
  jq -e 'length == 1 and all(.[ ];
    .params.activeUsers == "10000" and .params.activeSymbols == "512" and
    .params.maxInFlight == "256" and .params.operationsPerInvocation == "16384" and
    .params.targetOperationsPerSecond == "100000" and
    .secondaryMetrics.matchingWindowSamples.score > 0 and
    .secondaryMetrics.matchingFullWindowSamples.score > 0 and
    .secondaryMetrics.matchingRefillOperations.score > 0 and
    .secondaryMetrics.matchingProducerStarvationSamples.score == 0 and
    .secondaryMetrics.terminalTrades.score > 0)' "${result}" > /dev/null
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
  local profile_jvm_args=(
    "${JVM_ARGS[@]}"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:NativeMemoryTracking=summary"
    "-XX:+PrintNMTStatistics"
    "-XX:StartFlightRecording=filename=${ARTIFACT_DIR}/scale.jfr,settings=${JFR_SETTINGS_FILE},dumponexit=true"
    "-Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/scale-profile-gc.log:time,uptime,level,tags"
  )
  "${JAVA}" "${profile_jvm_args[@]}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.scaleMixedWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 -p trafficProfile=UNIFORM \
    -p hftRounds=1 -p hftBatchSize=4 -p lifecycleSymbolsPerRun="${LIFECYCLE_SYMBOL_BUDGET}" \
    -wi 2 -w 2s -i 1 -r 10s -f 0 \
    -rf json -rff "${ARTIFACT_DIR}/scale-profile.json" \
    > "${ARTIFACT_DIR}/scale-profile.log" 2>&1 &
  local profile_pid=$!
  capture_process_nmt "${profile_pid}" "${ARTIFACT_DIR}/scale"
  wait "${profile_pid}"
  sed -n '1,240p' "${ARTIFACT_DIR}/scale-profile.log"
  validate_jmh "${ARTIFACT_DIR}/scale-profile.json"
  [[ -s "${ARTIFACT_DIR}/scale.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/scale-profile-gc.log" ]]
  [[ -s "${ARTIFACT_DIR}/scale-nmt-baseline.txt" ]]
  [[ -s "${ARTIFACT_DIR}/scale-nmt-summary.diff.txt" ]]
  REQUIRE_WORKLOAD_LATENCY_CONTRACT='PLACE_ORDER=EXERCISED,TAKER_FILL=NOT_EXERCISED,CANCEL_ORDER=EXERCISED,AMEND_ORDER=NOT_EXERCISED,ORDER_BATCH=EXERCISED,TRIGGER_ORDER=EXERCISED,RISK_SCAN=EXERCISED,LIQUIDATION=EXERCISED,FUNDING=EXERCISED,ADL=EXERCISED,SETTLEMENT=NOT_EXERCISED,SNAPSHOT_RECOVERY=NOT_EXERCISED' \
    JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE}" "${JFR_ANALYZER}" \
    "${ARTIFACT_DIR}/scale.jfr" "${ARTIFACT_DIR}/scale-jfr-analysis"
}

run_soak() {
  local old_object_option=""
  local soak_jfr_settings="${JFR_SETTINGS_FILE}"
  if [[ "${SOAK_OLD_OBJECT_PATHS}" == "true" ]]; then
    old_object_option=",path-to-gc-roots=true"
    soak_jfr_settings="${ARTIFACT_DIR}/owner-commit-oldobject.jfc"
    bash "${OLD_OBJECT_JFC_GENERATOR}" "${JFR_SETTINGS_FILE}" "${soak_jfr_settings}"
  fi
  local soak_jvm_args=(
    "${JVM_ARGS[@]}"
    "-XX:NativeMemoryTracking=summary"
    "-XX:StartFlightRecording=filename=${ARTIFACT_DIR}/scale-soak.jfr,settings=${soak_jfr_settings},dumponexit=true${old_object_option}"
    "-Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/scale-soak-gc.log:time,uptime,level,tags"
  )
  "${JAVA}" "${soak_jvm_args[@]}" -cp "${JAR}" \
    com.surprising.aeron.service.LinearPerpetualScaleSoakMain \
    10000 512 512 5 10 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}" \
    "${SOAK_SECONDS}" "${SOAK_SAMPLE_SECONDS}" \
    > "${ARTIFACT_DIR}/scale-soak.jsonl" 2> "${ARTIFACT_DIR}/scale-soak.stderr.log" &
  local soak_pid=$!
  capture_nmt_baseline "${soak_pid}" "${ARTIFACT_DIR}/scale-soak"
  (
    while kill -0 "${soak_pid}" 2>/dev/null; do
      "${JAVA_HOME_25}/bin/jcmd" "${soak_pid}" VM.native_memory summary.diff \
        > "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.tmp" 2>/dev/null || true
      if [[ -s "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.tmp" ]]; then
        mv "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.tmp" \
          "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.txt"
      fi
      sleep "${SOAK_SAMPLE_SECONDS}"
    done
    rm -f "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.tmp"
  ) &
  local nmt_sampler_pid=$!
  wait "${soak_pid}"
  wait "${nmt_sampler_pid}" || true
  sed -n '1,240p' "${ARTIFACT_DIR}/scale-soak.jsonl"
  tail -n 1 "${ARTIFACT_DIR}/scale-soak.jsonl" | jq -e \
    '.type == "summary" and .status == "PASS" and .fundsInvariant == true
      and .terminalBusinessOperations > 0 and .listedSymbols == 512
      and .postGcSamples >= 3
      and .postGcLiveSetSlopeBytesPerSec <= .leakThresholds.liveSetBytesPerSec
      and .postGcOldGenerationSlopeBytesPerSec <= .leakThresholds.liveSetBytesPerSec
      and .postGcDirectSlopeBytesPerSec <= .leakThresholds.nativeBufferBytesPerSec
      and .postGcMappedSlopeBytesPerSec <= .leakThresholds.nativeBufferBytesPerSec
      and .postGcThreadSlopePerSec <= .leakThresholds.threadsPerSec
      and .postGcFdSlopePerSec <= .leakThresholds.fdsPerSec
      and .directPoolBalanceSlopePerSec <= .leakThresholds.poolBalancePerSec
      and .mappedPoolBalanceSlopePerSec <= .leakThresholds.poolBalancePerSec' \
    > "${ARTIFACT_DIR}/scale-soak.json"
  [[ -s "${ARTIFACT_DIR}/scale-soak-gc.log" ]]
  [[ -s "${ARTIFACT_DIR}/scale-soak.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/scale-soak-nmt-baseline.txt" ]]
  [[ -s "${ARTIFACT_DIR}/scale-soak-nmt-summary.diff.txt" ]]
  OLD_OBJECT_ESCALATION="${SOAK_OLD_OBJECT_PATHS}" JFR_SETTINGS_FILE="${soak_jfr_settings}" \
    "${JFR_ANALYZER}" \
    "${ARTIFACT_DIR}/scale-soak.jfr" "${ARTIFACT_DIR}/scale-soak-jfr-analysis"
}

run_capacity() {
  : > "${ARTIFACT_DIR}/scale-matrix.jsonl"
  run_probe_case density-extreme-u10000-s512-p20-o100 \
    10000 512 512 20 100 UNIFORM 1 4 "${LIFECYCLE_SYMBOL_BUDGET}"
  run_jmh_case density-extreme-512 10000 512 512 20 100 UNIFORM 1
}

run_saturation_case() {
  local in_flight="$1"
  local case_id="spsc-matcher-inflight-${in_flight}"
  local result="${ARTIFACT_DIR}/saturation-${case_id}.json"
  local saturation_args="${JVM_ARGS_STRING} -Dsurprising.benchmark.export-ack-interval=1024"
  "${JAVA}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.saturatedMatchingWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 \
    -p maxInFlight="${in_flight}" -p operationsPerInvocation="${SATURATION_OPERATIONS}" \
    -p targetOperationsPerSecond=100000 \
    -wi "${JMH_WARMUP_ITERATIONS}" -w "${JMH_WARMUP_SECONDS}s" \
    -i "${JMH_MEASUREMENT_ITERATIONS}" -r "${JMH_MEASUREMENT_SECONDS}s" \
    -f "${JMH_FORKS}" -t 1 \
    -jvmArgsAppend "${saturation_args}" -rf json -rff "${result}" \
    2>&1 | tee "${ARTIFACT_DIR}/saturation-${case_id}.log"
  validate_saturation_jmh "${result}"
}

run_saturation_profile() {
  local profile_jvm_args=(
    "${JVM_ARGS[@]}"
    "-Dsurprising.benchmark.export-ack-interval=1024"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:NativeMemoryTracking=summary"
    "-XX:+PrintNMTStatistics"
    "-XX:StartFlightRecording=filename=${ARTIFACT_DIR}/saturation.jfr,settings=${JFR_SETTINGS_FILE},dumponexit=true"
    "-Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/saturation-gc.log:time,uptime,level,tags"
  )
  "${JAVA}" "${profile_jvm_args[@]}" -jar "${JAR}" 'LinearPerpetualCoreBenchmark.saturatedMatchingWorkload' \
    -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 \
    -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 \
    -p maxInFlight=256 -p operationsPerInvocation="${SATURATION_OPERATIONS}" \
    -p targetOperationsPerSecond=100000 \
    -wi 1 -w 3s -i 1 -r 10s -f 0 -t 1 \
    -rf json -rff "${ARTIFACT_DIR}/saturation-profile.json" \
    > "${ARTIFACT_DIR}/saturation-profile.log" 2>&1 &
  local profile_pid=$!
  capture_process_nmt "${profile_pid}" "${ARTIFACT_DIR}/saturation"
  wait "${profile_pid}"
  sed -n '1,240p' "${ARTIFACT_DIR}/saturation-profile.log"
  validate_saturation_jmh "${ARTIFACT_DIR}/saturation-profile.json"
  [[ -s "${ARTIFACT_DIR}/saturation.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/saturation-gc.log" ]]
  [[ -s "${ARTIFACT_DIR}/saturation-nmt-baseline.txt" ]]
  [[ -s "${ARTIFACT_DIR}/saturation-nmt-summary.diff.txt" ]]
  REQUIRE_WORKLOAD_LATENCY_CONTRACT='PLACE_ORDER=EXERCISED,TAKER_FILL=NOT_EXERCISED,CANCEL_ORDER=NOT_EXERCISED,AMEND_ORDER=NOT_EXERCISED,ORDER_BATCH=NOT_EXERCISED,TRIGGER_ORDER=NOT_EXERCISED,RISK_SCAN=NOT_EXERCISED,LIQUIDATION=NOT_EXERCISED,FUNDING=NOT_EXERCISED,ADL=NOT_EXERCISED,SETTLEMENT=NOT_EXERCISED,SNAPSHOT_RECOVERY=NOT_EXERCISED' \
    JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE}" "${JFR_ANALYZER}" \
    "${ARTIFACT_DIR}/saturation.jfr" "${ARTIFACT_DIR}/saturation-jfr-analysis"
}

run_saturation() {
  run_saturation_case 256
  run_saturation_profile
  jq -s 'add' "${ARTIFACT_DIR}"/saturation-*.json > "${ARTIFACT_DIR}/saturation-matrix.json"
}

validate_owner_commit_jmh() {
  local result="$1"
  jq -e 'length == 6 and all(.[ ];
    .primaryMetric.score > 0 and
    .params.activeUsers == "10000" and
    .params.listedSymbols == "512" and
    .params.accountLanes == "4" and
    .params.positionsPerUser == "5" and
    .params.ordersPerUser == "10" and
    .params.maxInFlight == "256" and
    .params.operationsPerInvocation == "16384" and
    .params.targetOperationsPerSecond == "100000" and
    .secondaryMetrics.acceptedBusinessOperations.score ==
      .secondaryMetrics.terminalBusinessOperations.score and
    .secondaryMetrics.acceptedCoreMessages.score == .secondaryMetrics.terminalCoreMessages.score and
    .secondaryMetrics.acceptedTerminalBusinessGap.score == 0 and
    .secondaryMetrics.acceptedTerminalCoreGap.score == 0 and
    .secondaryMetrics.unfinishedBusinessOperations.score == 0 and
    .secondaryMetrics.unfinishedCoreMessages.score == 0 and
    .secondaryMetrics.endBacklog.score == 0 and
    .secondaryMetrics.rejectedOperations.score == 0 and
    .secondaryMetrics.errorOperations.score == 0 and
    .secondaryMetrics.timeoutOperations.score == 0 and
    .secondaryMetrics.patchItems.score > 0 and
    .secondaryMetrics.patchBytes.score > 0 and
    .secondaryMetrics.batchItems.score > 0 and
    .secondaryMetrics.maximumBatchSize.score > 0 and
    (if (.benchmark | endswith("ownerCommitSnapshotRecovery")) then
      .secondaryMetrics.snapshotBytes.score > 0
    else .secondaryMetrics.snapshotBytes.score == 0 end) and
    .secondaryMetrics.entryTerminalP999Nanos.score >= .secondaryMetrics.entryTerminalP99Nanos.score)' \
    "${result}" > /dev/null
}

run_owner_commit() {
  local benchmark='OwnerCommitPatchBenchmark.*'
  local params=(
    -p activeUsers=10000 -p listedSymbols=512 -p accountLanes=4
    -p positionsPerUser=5 -p ordersPerUser=10 -p maxInFlight=256
    -p operationsPerInvocation=16384 -p targetOperationsPerSecond=100000
  )
  "${JAVA}" -jar "${JAR}" "${benchmark}" "${params[@]}" \
    -wi "${JMH_WARMUP_ITERATIONS}" -w "${JMH_WARMUP_SECONDS}s" \
    -i "${JMH_MEASUREMENT_ITERATIONS}" -r "${JMH_MEASUREMENT_SECONDS}s" -f "${JMH_FORKS}" -t 1 \
    -jvmArgsAppend "${JVM_ARGS_STRING}" -rf json -rff "${ARTIFACT_DIR}/owner-commit.json" \
    2>&1 | tee "${ARTIFACT_DIR}/owner-commit.log"
  validate_owner_commit_jmh "${ARTIFACT_DIR}/owner-commit.json"

  "${JAVA}" -jar "${JAR}" "${benchmark}" "${params[@]}" \
    -wi 2 -w 2s -i 3 -r 3s -f 1 -t 1 -prof gc \
    -jvmArgsAppend "${JVM_ARGS_STRING}" -rf json -rff "${ARTIFACT_DIR}/owner-commit-gc.json" \
    2>&1 | tee "${ARTIFACT_DIR}/owner-commit-gc.log"
  validate_owner_commit_jmh "${ARTIFACT_DIR}/owner-commit-gc.json"
  jq -e 'all(.[ ]; .secondaryMetrics["gc.alloc.rate"].score >= 0
    and .secondaryMetrics["gc.alloc.rate.norm"].score >= 0)' \
    "${ARTIFACT_DIR}/owner-commit-gc.json" > /dev/null

  local profile_jvm_args=(
    "${JVM_ARGS[@]}"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:NativeMemoryTracking=summary"
    "-XX:+PrintNMTStatistics"
    "-XX:StartFlightRecording=filename=${ARTIFACT_DIR}/owner-commit.jfr,settings=${JFR_SETTINGS_FILE},dumponexit=true"
    "-Xlog:gc*,safepoint:file=${ARTIFACT_DIR}/owner-commit-profile-gc.log:time,uptime,level,tags"
  )
  "${JAVA}" "${profile_jvm_args[@]}" -jar "${JAR}" "${benchmark}" "${params[@]}" \
    -wi 2 -w 2s -i 1 -r 10s -f 0 -t 1 \
    -rf json -rff "${ARTIFACT_DIR}/owner-commit-profile.json" \
    > "${ARTIFACT_DIR}/owner-commit-profile.log" 2>&1 &
  local profile_runner_pid=$!
  capture_process_nmt "${profile_runner_pid}" "${ARTIFACT_DIR}/owner-commit"
  wait "${profile_runner_pid}"
  sed -n '1,240p' "${ARTIFACT_DIR}/owner-commit-profile.log"
  validate_owner_commit_jmh "${ARTIFACT_DIR}/owner-commit-profile.json"
  [[ -s "${ARTIFACT_DIR}/owner-commit.jfr" ]]
  [[ -s "${ARTIFACT_DIR}/owner-commit-profile-gc.log" ]]
  [[ -s "${ARTIFACT_DIR}/owner-commit-nmt-baseline.txt" ]]
  [[ -s "${ARTIFACT_DIR}/owner-commit-nmt-summary.diff.txt" ]]
  REQUIRE_OWNER_MEASUREMENTS=true JAVA_HOME="${JAVA_HOME_25}" \
    JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE}" "${JFR_ANALYZER}" \
    "${ARTIFACT_DIR}/owner-commit.jfr" "${ARTIFACT_DIR}/owner-commit-jfr-analysis"
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
  owner-commit) package_benchmark; run_owner_commit ;;
  tests) run_targeted_tests ;;
  all) run_targeted_tests; package_benchmark; run_probe_matrix; run_jmh_matrix; run_gc; run_profile; run_saturation; run_owner_commit; run_soak ;;
  *) echo "Usage: $0 [tests|probe|jmh|gc|profile|soak|capacity|saturation|owner-commit|all]" >&2; exit 2 ;;
esac
echo "Scale qualification artifacts: ${ARTIFACT_DIR}"
