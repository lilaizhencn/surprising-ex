#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <recording.jfr> [output-directory]" >&2
  exit 2
fi

RECORDING="$1"
if [[ ! -s "${RECORDING}" ]]; then
  echo "missing or empty JFR recording: ${RECORDING}" >&2
  exit 2
fi

JFR_HOME="${SURPRISING_JAVA_HOME:-${JAVA_HOME:-}}"
JAVA_BIN="${JFR_HOME:+${JFR_HOME}/bin/}java"
JFR_BIN="${JFR_HOME:+${JFR_HOME}/bin/}jfr"
JAVA_VERSION="$(${JAVA_BIN} -version 2>&1)"
if [[ ( "${JAVA_VERSION}" != *'version "25"'* && "${JAVA_VERSION}" != *'version "25.'* ) \
    || "${JAVA_VERSION}" == *'OpenJ9'* ]] \
    || [[ "${JAVA_VERSION}" != *'HotSpot'* && "${JAVA_VERSION}" != *'OpenJDK 64-Bit Server VM'* ]]; then
  echo "JFR analysis requires HotSpot JDK 25; found:" >&2
  echo "${JAVA_VERSION}" >&2
  exit 2
fi
if ! command -v "${JFR_BIN}" > /dev/null 2>&1; then
  echo "JFR command is unavailable: ${JFR_BIN}" >&2
  exit 2
fi
if ! command -v jq > /dev/null 2>&1; then
  echo "jq is required to analyze owner-commit JFR evidence" >&2
  exit 2
fi

OUTPUT_DIR="${2:-$(dirname "${RECORDING}")/owner-commit-jfr-analysis}"
OWNER_MEASUREMENTS="${REQUIRE_OWNER_MEASUREMENTS:-false}"
WORKLOAD_LATENCY_CONTRACT="${REQUIRE_WORKLOAD_LATENCY_CONTRACT:-}"
OLD_OBJECT_ESCALATION="${OLD_OBJECT_ESCALATION:-false}"
JFR_SETTINGS_FILE="${JFR_SETTINGS_FILE:-}"
MAX_EXCEPTIONS="${JFR_MAX_EXCEPTIONS:-0}"
MAX_OWNER_SYNC_IO_EVENTS="${JFR_MAX_OWNER_SYNC_IO_EVENTS:-0}"
MAX_OWNER_SYNC_IO_BYTES="${JFR_MAX_OWNER_SYNC_IO_BYTES:-0}"
if [[ -z "${JFR_SETTINGS_FILE}" || ! -s "${JFR_SETTINGS_FILE}" ]]; then
  echo "JFR_SETTINGS_FILE must identify the explicit settings used by the recording" >&2
  exit 2
fi
if [[ "${OLD_OBJECT_ESCALATION}" != "true" && "${OLD_OBJECT_ESCALATION}" != "false" ]]; then
  echo "OLD_OBJECT_ESCALATION must be true or false" >&2
  exit 2
fi
read_jfc_period_millis() {
  local event_name="$1"
  sed -n "/<event name=\"${event_name}\"/p" "${JFR_SETTINGS_FILE}" \
    | sed -n 's/.*<setting name="period">\([0-9][0-9]*\) ms<\/setting>.*/\1/p' \
    | head -n 1
}
EXECUTION_SAMPLE_PERIOD_MILLIS="$(read_jfc_period_millis 'jdk.ExecutionSample')"
NATIVE_SAMPLE_PERIOD_MILLIS="$(read_jfc_period_millis 'jdk.NativeMethodSample')"
if [[ -z "${EXECUTION_SAMPLE_PERIOD_MILLIS}" || -z "${NATIVE_SAMPLE_PERIOD_MILLIS}" \
    || "${EXECUTION_SAMPLE_PERIOD_MILLIS}" != "${NATIVE_SAMPLE_PERIOD_MILLIS}" ]]; then
  echo "JFC must enable ExecutionSample and NativeMethodSample with one explicit integer-ms period" >&2
  exit 2
fi
EXECUTION_SAMPLE_PERIOD_NANOS=$((EXECUTION_SAMPLE_PERIOD_MILLIS * 1000000))
for threshold in "${EXECUTION_SAMPLE_PERIOD_NANOS}" "${MAX_EXCEPTIONS}" \
  "${MAX_OWNER_SYNC_IO_EVENTS}" "${MAX_OWNER_SYNC_IO_BYTES}"; do
  if [[ ! "${threshold}" =~ ^[0-9]+$ ]]; then
    echo "JFR analyzer thresholds and sample period must be non-negative integers" >&2
    exit 2
  fi
done
if [[ "${EXECUTION_SAMPLE_PERIOD_NANOS}" -eq 0 ]]; then
  echo "JFR execution sample period must be positive" >&2
  exit 2
fi
mkdir -p "${OUTPUT_DIR}"
SUMMARY="${OUTPUT_DIR}/jfr-summary.txt"
"${JFR_BIN}" summary "${RECORDING}" | tee "${SUMMARY}"
METADATA="${OUTPUT_DIR}/jfr-metadata.txt"
"${JFR_BIN}" metadata "${RECORDING}" > "${METADATA}"

jfc_enables() {
  local event_name="$1"
  sed -n "/<event name=\"${event_name}\"/p" "${JFR_SETTINGS_FILE}" \
    | grep -q '<setting name="enabled">true</setting>'
}
metadata_supports() {
  local event_name="$1"
  grep -Fq "${event_name}" "${METADATA}"
}
bool_for_all() {
  local check="$1"
  shift
  local event_name
  for event_name in "$@"; do
    if ! "${check}" "${event_name}"; then
      printf 'false'
      return
    fi
  done
  printf 'true'
}
CODE_CACHE_CONFIGURED="$(bool_for_all jfc_enables jdk.CodeCacheStatistics)"
CODE_CACHE_SUPPORTED="$(bool_for_all metadata_supports jdk.CodeCacheStatistics)"
METASPACE_CONFIGURED="$(bool_for_all jfc_enables jdk.MetaspaceSummary jdk.MetaspaceGCThreshold)"
METASPACE_SUPPORTED="$(bool_for_all metadata_supports jdk.MetaspaceSummary jdk.MetaspaceGCThreshold)"
DEOPT_CONFIGURED="$(bool_for_all jfc_enables jdk.Deoptimization)"
DEOPT_SUPPORTED="$(bool_for_all metadata_supports jdk.Deoptimization)"
CLASS_CONFIGURED="$(bool_for_all jfc_enables jdk.ClassLoad jdk.ClassUnload)"
CLASS_SUPPORTED="$(bool_for_all metadata_supports jdk.ClassLoad jdk.ClassUnload)"
OLD_OBJECT_CONFIGURED="$(bool_for_all jfc_enables jdk.OldObjectSample)"
OLD_OBJECT_SUPPORTED="$(bool_for_all metadata_supports jdk.OldObjectSample)"

emit_group() {
  local name="$1" events="$2" required="$3"
  local json="${OUTPUT_DIR}/${name}.json"
  local csv="${OUTPUT_DIR}/${name}.csv"
  "${JFR_BIN}" print --json --events "${events}" "${RECORDING}" > "${json}"
  jq -r '(["eventType", "values"] | @csv),
    (.recording.events[]? | [.type, (.values | tojson)] | @csv)' "${json}" > "${csv}"
  local count
  count="$(jq '[.recording.events[]?] | length' "${json}")"
  if [[ "${required}" == "required" && "${count}" -eq 0 ]]; then
    echo "missing required JFR metric group: ${name} (${events})" >&2
    exit 3
  fi
}

emit_group data-loss 'jdk.DataLoss' optional
DATA_LOSS_COUNT="$(jq '[.recording.events[]?] | length' "${OUTPUT_DIR}/data-loss.json")"
if [[ "${DATA_LOSS_COUNT}" -ne 0 ]]; then
  echo "JFR DataLoss detected; recording is invalid: ${RECORDING}" >&2
  exit 4
fi

emit_group cpu \
  'jdk.CPULoad,jdk.ThreadCPULoad,jdk.ExecutionSample,jdk.NativeMethodSample' required
if [[ "${OWNER_MEASUREMENTS}" == "true" ]]; then
  emit_group owner-measurements 'com.surprising.OwnerCommitMeasurement' required
else
  emit_group owner-measurements 'com.surprising.OwnerCommitMeasurement' optional
fi
emit_group workload-measurements \
  'com.surprising.LinearPerpetualWorkload,com.surprising.LinearPerpetualSaturation,com.surprising.LinearPerpetualBusinessLatency' optional
emit_group allocations \
  'jdk.ObjectAllocationSample,jdk.ThreadAllocationStatistics' required
emit_group heap-gc \
  'jdk.GCHeapSummary,jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCPhasePauseLevel1,jdk.GCPhasePauseLevel2,jdk.GCPhasePauseLevel3,jdk.GCPhaseConcurrent,jdk.GCConfiguration,jdk.YoungGenerationConfiguration' required
emit_group gc-failure-signals \
  'jdk.ZAllocationStall,jdk.G1EvacuationFailure,jdk.PromotionFailed,jdk.EvacuationFailed,jdk.ShenandoahHeapRegionStateChange' optional
emit_group native-direct-memory \
  'jdk.NativeMemoryUsage,jdk.DirectBufferStatistics' required
emit_group locks-parks \
  'jdk.JavaMonitorEnter,jdk.JavaMonitorWait,jdk.ThreadSleep' optional
emit_group thread-lifecycle \
  'jdk.ThreadStart,jdk.ThreadEnd,jdk.JavaThreadStatistics' required
emit_group safepoints \
  'jdk.SafepointBegin,jdk.SafepointStateSynchronization,jdk.SafepointCleanup,jdk.SafepointCleanupTask,jdk.SafepointEnd,jdk.ExecuteVMOperation' required
emit_group jit \
  'jdk.Compilation,jdk.CompilationFailure,jdk.Deoptimization,jdk.CodeCacheStatistics,jdk.ClassLoad,jdk.ClassUnload,jdk.MetaspaceSummary,jdk.MetaspaceGCThreshold' required
emit_group io 'jdk.FileRead,jdk.FileWrite,jdk.SocketRead,jdk.SocketWrite' optional
emit_group exceptions 'jdk.JavaExceptionThrow,jdk.JavaErrorThrow' optional
emit_group old-objects 'jdk.OldObjectSample' optional
emit_group system-container \
  'jdk.OSInformation,jdk.PhysicalMemory,jdk.SwapSpace,jdk.ContainerConfiguration,jdk.ContainerCPUUsage,jdk.ContainerCPUThrottling,jdk.ContainerMemoryUsage,jdk.SystemProcess' required

# High-frequency park and TLAB events expand to multi-gigabyte JSON because every row repeats its stack.
# Preserve bounded, inspectable JDK aggregations instead of materializing those raw event streams.
"${JFR_BIN}" view --width 200 contention-by-site "${RECORDING}" > "${OUTPUT_DIR}/contention-by-site.txt"
"${JFR_BIN}" view --width 200 contention-by-thread "${RECORDING}" > "${OUTPUT_DIR}/contention-by-thread.txt"
"${JFR_BIN}" view --width 200 allocation-by-class "${RECORDING}" > "${OUTPUT_DIR}/allocation-by-class.txt"
"${JFR_BIN}" view --width 200 allocation-by-site "${RECORDING}" > "${OUTPUT_DIR}/allocation-by-site.txt"
"${JFR_BIN}" view --width 200 allocation-by-thread "${RECORDING}" > "${OUTPUT_DIR}/allocation-by-thread.txt"

if ! jq -e '[.recording.events[]? | select(.type == "jdk.NativeMemoryUsage")] | length > 0' \
    "${OUTPUT_DIR}/native-direct-memory.json" > /dev/null; then
  echo "missing NMT evidence (jdk.NativeMemoryUsage); rerun with NativeMemoryTracking enabled" >&2
  exit 3
fi

jq -n \
  --slurpfile cpu "${OUTPUT_DIR}/cpu.json" \
  --slurpfile allocations "${OUTPUT_DIR}/allocations.json" \
  --slurpfile heapGc "${OUTPUT_DIR}/heap-gc.json" \
  --slurpfile gcSignals "${OUTPUT_DIR}/gc-failure-signals.json" \
  --slurpfile nativeMemory "${OUTPUT_DIR}/native-direct-memory.json" \
  --slurpfile locks "${OUTPUT_DIR}/locks-parks.json" \
  --slurpfile safepoints "${OUTPUT_DIR}/safepoints.json" \
  --slurpfile jit "${OUTPUT_DIR}/jit.json" \
  --slurpfile io "${OUTPUT_DIR}/io.json" \
  --slurpfile exceptions "${OUTPUT_DIR}/exceptions.json" \
  --slurpfile oldObjects "${OUTPUT_DIR}/old-objects.json" \
  --slurpfile system "${OUTPUT_DIR}/system-container.json" \
  --slurpfile owner "${OUTPUT_DIR}/owner-measurements.json" \
  --slurpfile workload "${OUTPUT_DIR}/workload-measurements.json" \
  --slurpfile threads "${OUTPUT_DIR}/thread-lifecycle.json" \
  --argjson executionSamplePeriodNanos "${EXECUTION_SAMPLE_PERIOD_NANOS}" \
  --argjson maxExceptions "${MAX_EXCEPTIONS}" \
  --argjson maxOwnerSyncIoEvents "${MAX_OWNER_SYNC_IO_EVENTS}" \
  --argjson maxOwnerSyncIoBytes "${MAX_OWNER_SYNC_IO_BYTES}" \
  --argjson codeCacheConfigured "${CODE_CACHE_CONFIGURED}" \
  --argjson codeCacheSupported "${CODE_CACHE_SUPPORTED}" \
  --argjson metaspaceConfigured "${METASPACE_CONFIGURED}" \
  --argjson metaspaceSupported "${METASPACE_SUPPORTED}" \
  --argjson deoptConfigured "${DEOPT_CONFIGURED}" \
  --argjson deoptSupported "${DEOPT_SUPPORTED}" \
  --argjson classConfigured "${CLASS_CONFIGURED}" \
  --argjson classSupported "${CLASS_SUPPORTED}" \
  --argjson oldObjectEscalation "${OLD_OBJECT_ESCALATION}" \
  --argjson oldObjectConfigured "${OLD_OBJECT_CONFIGURED}" \
  --argjson oldObjectSupported "${OLD_OBJECT_SUPPORTED}" \
  --arg workloadLatencyContract "${WORKLOAD_LATENCY_CONTRACT}" '
  def events($doc): [$doc[0].recording.events[]?];
  def frame($event):
    ($event.values.stackTrace.frames[0].method.type.name // "unknown") + "." +
    ($event.values.stackTrace.frames[0].method.name // "unknown");
  def thread($event):
    ($event.values.sampledThread.javaName // $event.values.eventThread.javaName //
     $event.values.thread.javaName // "unknown");
  def requiredRoles:
    ["owner", "matcher", "risk", "snapshot", "projection", "core-fact/exporter",
     "Aeron", "Kafka", "peripheral", "lane", "GC", "compiler"];
  def role($name):
    if $name | test("matcher|matching|exchange-core|orderbook"; "i") then "matcher"
    elif $name | test("risk|liquidat|funding|adl|insurance|margin"; "i") then "risk"
    elif $name | test("snapshot|recovery|restore|snapshotcodec"; "i") then "snapshot"
    elif $name | test("projector|projection"; "i") then "projection"
    elif $name | test("core[- ]?fact|coreexport|materializecorefact|exporter"; "i") then "core-fact/exporter"
    elif $name | test("aeron|conductor|archive|cluster"; "i") then "Aeron"
    elif $name | test("kafka"; "i") then "Kafka"
    elif $name | test("account-lane|settlement-lane|lifecycle-lane|(^|[-_])lane([-_]|$)"; "i") then "lane"
    elif $name | test("zgc|gc thread|g1 |shenandoah"; "i") then "GC"
    elif $name | test("compilerthread|c1 compiler|c2 compiler|jit"; "i") then "compiler"
    elif $name | test("owner|ownercommit|core-command|trading-core|tradingcoreruntime"; "i") then "owner"
    elif $name | test("commonpool|reference handler|finalizer|signal dispatcher|notification|cleaner|vm thread|service thread|jfr|attach listener|process reaper|virtualthread|carrier|http|netty|grpc|jdbc|database|scheduler|timer|watchdog|stdout|stderr|jmh|main"; "i") then "peripheral"
    else "unclassified" end;
  def stackText:
    (.values.stackTrace.frames // [] | map(
      (.method.type.name // "") + "." + (.method.name // "")) | join(" <- "));
  def eventRole:
    (role(thread(.))) as $threadRole |
    (role(stackText)) as $stackRole |
    if (["matcher", "risk", "snapshot", "projection", "core-fact/exporter", "Aeron", "Kafka", "lane"] |
        index($stackRole)) != null then $stackRole
    elif $threadRole != "unclassified" then $threadRole
    else $stackRole end;
  def top($items; keyFilter):
    [$items[] | {key: keyFilter}] | group_by(.key) |
    map({name: .[0].key, samples: length}) | sort_by(-.samples) | .[0:25];
  def allocationWeightBytes:
    if .type == "jdk.ObjectAllocationSample" then
      (.values.weight // .values.allocationSize // .values.objectSize // 0)
    elif .type == "jdk.ObjectAllocationInNewTLAB" then
      (.values.tlabSize // .values.allocationSize // .values.objectSize // 0)
    elif .type == "jdk.ObjectAllocationOutsideTLAB" then
      (.values.allocationSize // .values.objectSize // 0)
    else 0 end;
  def singleObjectBytes:
    (.values.allocationSize // .values.objectSize // null);
  def allocationType:
    (.values.objectClass.name // .values.objectClass // "unknown");
  def topAllocationTypes($items):
    [$items[] | select(.type != "jdk.ThreadAllocationStatistics") |
      {name: allocationType, bytes: allocationWeightBytes}] |
    group_by(.name) | map({name: .[0].name, samples: length,
      sampledBytes: (map(.bytes) | add // 0)}) | sort_by(-.sampledBytes) | .[0:25];
  def threadAllocationDelta($items):
    [$items[] | select(.type == "jdk.ThreadAllocationStatistics") |
      {key: thread(.), allocated: (.values.allocated // 0)}] |
    group_by(.key) |
    map((map(.allocated) | max) - (map(.allocated) | min)) | add // 0;
  def terminalOperations($items):
    [$items[] | (.values.terminalBusinessOperations // .values.operationsPerInvocation // 0)] | add // 0;
  def durationNanos:
    if type == "number" then .
    elif type == "string" and test("^PT[0-9.]+S$") then
      (capture("^PT(?<seconds>[0-9.]+)S$").seconds | tonumber) * 1000000000
    else 0 end;
  def eventSamplePeriodNanos($configured):
    if .values.samplePeriodNanos? != null then .values.samplePeriodNanos
    elif .values.periodNanos? != null then .values.periodNanos
    elif .values.samplePeriod? != null then (.values.samplePeriod | durationNanos)
    elif .values.period? != null then (.values.period | durationNanos)
    elif .values.duration? != null and (.values.duration | durationNanos) > 0 then
      (.values.duration | durationNanos)
    else $configured end;
  def eventSamplePeriodSource:
    if (.values.samplePeriodNanos? != null or .values.periodNanos? != null or
        .values.samplePeriod? != null or .values.period? != null or
        (.values.duration? != null and (.values.duration | durationNanos) > 0))
    then "event-metadata" else "explicit-recording-configuration" end;
  def eventSeconds:
    (.values.startTime // .values.eventTime // null) as $time |
    if $time == null then null
    elif $time | type == "number" then $time
    elif $time | type == "string" then
      try ($time | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601) catch null
    else null end;
  def recordingSeconds($items):
    [$items[] | eventSeconds] | map(select(. != null)) as $times |
    if ($times | length) < 2 then 1 else ([($times | max) - ($times | min), 0.001] | max) end;
  def busySpinSample:
    (stackText | test("onSpinWait|BusySpin|busySpin|BusySpinWaitStrategy"; "i"));
  def stateRows($cpu; $locks; $sampleNanos):
    ([$cpu[] | select(.type == "jdk.ExecutionSample" or .type == "jdk.NativeMethodSample") |
      {thread: thread(.), role: eventRole, state: "RUNNABLE",
       nanos: eventSamplePeriodNanos($sampleNanos), periodSource: eventSamplePeriodSource,
       busySpin: busySpinSample}]
    + [$locks[] | select(.type == "jdk.JavaMonitorEnter") |
      {thread: thread(.), role: eventRole, state: "BLOCKED",
       nanos: (.values.duration // 0 | durationNanos), busySpin: false}]
    + [$locks[] | select(.type == "jdk.JavaMonitorWait" or .type == "jdk.ThreadSleep") |
      {thread: thread(.), role: eventRole, state: "WAITING",
       nanos: (.values.duration // 0 | durationNanos), busySpin: false}]
    + [$locks[] | select(.type == "jdk.ThreadPark") |
      {thread: thread(.), role: eventRole, state: "PARKED",
       nanos: (.values.duration // 0 | durationNanos), busySpin: false}]);
  def perThreadStates($rows):
    $rows | group_by(.thread) | map(. as $group |
      ([$group[].nanos] | add // 0) as $total |
      {thread: $group[0].thread, role: $group[0].role, totalObservedNanos: $total,
       states: ($group | group_by(.state) | map({state: .[0].state,
         nanos: ([.[].nanos] | add // 0),
         percent: (if $total == 0 then 0 else (([.[].nanos] | add // 0) * 100 / $total) end)}))});
  def perRoleStates($rows):
    $rows | group_by(.role) | map(. as $group |
      ([$group[].nanos] | add // 0) as $total |
      {role: $group[0].role, totalObservedNanos: $total,
       states: ($group | group_by(.state) | map({state: .[0].state,
         nanos: ([.[].nanos] | add // 0),
         percent: (if $total == 0 then 0 else (([.[].nanos] | add // 0) * 100 / $total) end)}))});
  def requiredRoleCoverage($rows):
    [requiredRoles[] as $required |
      ([$rows[] | select(.role == $required) | .nanos] | add // 0) as $observed |
      if $observed > 0 then
        {role: $required, status: "OBSERVED", observedNanos: $observed, reason: null}
      else
        {role: $required, status: "N/A", observedNanos: 0,
         reason: "no matching JFR state event in this recording"}
      end];
  def durationTop($items; keyFilter):
    [$items[] | {key: keyFilter,
      nanos: (.values.duration // 0 | durationNanos)}] |
    group_by(.key) | map({name: .[0].key, events: length,
      totalDurationNanos: ([.[].nanos] | add // 0),
      maxDurationNanos: ([.[].nanos] | max // 0)}) |
    sort_by(-.totalDurationNanos) | .[0:25];
  def compilationMethod:
    ((.values.method.type.name // .values.method.type // "unknown") + "." +
     (.values.method.name // "unknown"));
  def telemetryCoverage($family; $configured; $supported; $count):
    if $configured != true then
      {family: $family, status: "UNKNOWN", events: $count,
       configured: false, supported: $supported,
       reason: "required event family is not enabled by the qualification JFC"}
    elif $supported != true then
      {family: $family, status: "N/A", events: 0,
       configured: true, supported: false,
       reason: "event family is enabled but absent from this JVM recording metadata"}
    elif $count > 0 then
      {family: $family, status: "OBSERVED", events: $count,
       configured: true, supported: true, reason: null}
    else
      {family: $family, status: "MISSING", events: 0,
       configured: true, supported: true,
       reason: "configured and supported event family emitted no recording event"}
    end;
  def ioBytes:
    (.values.bytesRead // .values.bytesWritten // .values.bytes // 0);
  def percentile($values; $fraction):
    ($values | sort) as $sorted |
    if ($sorted | length) == 0 then 0 else
      $sorted[((($sorted | length) * $fraction | ceil) - 1) | if . < 0 then 0 else . end]
    end;
  def parseHistogram:
    if type != "string" then error("latency histogram counts must be encoded")
    else split(",") | map(tonumber) |
      if length != 64 then error("latency histogram must contain 64 buckets") else . end
    end;
  def mergeHistograms($encoded):
    reduce ($encoded[] | parseHistogram) as $hist
      ([range(0; 64) | 0]; reduce range(0; 64) as $index
        (.; .[$index] += $hist[$index]));
  def pow2($exponent): reduce range(0; $exponent) as $ignored (1; . * 2);
  def histogramPercentile($hist; $fraction):
    ($hist | add // 0) as $total |
    if $total == 0 then 0 else
      ($total * $fraction | ceil) as $target |
      (reduce range(0; 64) as $index ({cumulative: 0, value: null};
        if .value == null then
          .cumulative += $hist[$index] |
          if .cumulative >= $target then
            .value = (if $index == 63 then 9223372036854775807 else pow2($index) end)
          else . end
        else . end) | .value)
    end;
  def ownerLatency($events):
    [$events[] | select(.values.businessType != null) | .values] | group_by(.businessType) | map(
      . as $group |
      mergeHistograms([$group[].entryAcceptedHistogramCounts]) as $entryAcceptedHistogram |
      mergeHistograms([$group[].acceptedTerminalHistogramCounts]) as $acceptedTerminalHistogram |
      mergeHistograms([$group[].entryTerminalHistogramCounts]) as $entryTerminalHistogram | {
        businessType: $group[0].businessType,
        loadModel: $group[0].loadModel,
        coordinatedOmissionCorrected: all($group[]; .coordinatedOmissionCorrected == true),
        invocationSummaries: ($group | length),
        operationsPerInvocation: $group[0].operationsPerInvocation,
        targetOperationsPerSecond: $group[0].targetOperationsPerSecond,
        latencySamples: ([$group[].latencySamples] | add // 0),
        histogramLowestNanos: $group[0].histogramLowestNanos,
        histogramHighestNanos: $group[0].histogramHighestNanos,
        timeoutNanos: $group[0].timeoutNanos,
        latencyUnit: $group[0].latencyUnit,
        classificationSource: $group[0].classificationSource,
        scheduledBusinessOperations: ([$group[].scheduledBusinessOperations] | add // 0),
        terminalBusinessOperations: ([$group[].terminalBusinessOperations] | add // 0),
        terminalCoreMessages: ([$group[].terminalCoreMessages] | add // 0),
        quantileAggregation: "MERGED_LOG2_HISTOGRAM_COUNTS",
        entryAcceptedHistogramCounts: $entryAcceptedHistogram,
        acceptedTerminalHistogramCounts: $acceptedTerminalHistogram,
        entryTerminalHistogramCounts: $entryTerminalHistogram,
        entryAcceptedP50Nanos: histogramPercentile($entryAcceptedHistogram; 0.50),
        entryAcceptedP90Nanos: histogramPercentile($entryAcceptedHistogram; 0.90),
        entryAcceptedP95Nanos: histogramPercentile($entryAcceptedHistogram; 0.95),
        entryAcceptedP99Nanos: histogramPercentile($entryAcceptedHistogram; 0.99),
        entryAcceptedP999Nanos: histogramPercentile($entryAcceptedHistogram; 0.999),
        entryAcceptedMaxNanos: ([$group[].entryAcceptedMaxNanos] | max // 0),
        acceptedTerminalP50Nanos: histogramPercentile($acceptedTerminalHistogram; 0.50),
        acceptedTerminalP90Nanos: histogramPercentile($acceptedTerminalHistogram; 0.90),
        acceptedTerminalP95Nanos: histogramPercentile($acceptedTerminalHistogram; 0.95),
        acceptedTerminalP99Nanos: histogramPercentile($acceptedTerminalHistogram; 0.99),
        acceptedTerminalP999Nanos: histogramPercentile($acceptedTerminalHistogram; 0.999),
        acceptedTerminalMaxNanos: ([$group[].acceptedTerminalMaxNanos] | max // 0),
        entryTerminalP50Nanos: histogramPercentile($entryTerminalHistogram; 0.50),
        entryTerminalP90Nanos: histogramPercentile($entryTerminalHistogram; 0.90),
        entryTerminalP95Nanos: histogramPercentile($entryTerminalHistogram; 0.95),
        entryTerminalP99Nanos: histogramPercentile($entryTerminalHistogram; 0.99),
        entryTerminalP999Nanos: histogramPercentile($entryTerminalHistogram; 0.999),
        entryTerminalMaxNanos: ([$group[].entryTerminalMaxNanos] | max // 0)
      });
  def latencyQualification($contract; $latencies):
    if $contract == "" then [] else
      [$contract | split(",")[] | split("=") |
        {businessType: .[0], expectedStatus: .[1]}] |
      map(. as $expected |
        ([$latencies[] | select(.businessType == $expected.businessType)]) as $matches |
        ($matches | length) as $count |
        $expected + {status: (if $count == 1 then "EXERCISED" else "NOT_EXERCISED" end),
          eventCount: $count, latency: ($matches[0] // null),
          reason: (if $count == 0 then
            "scenario does not generate a real terminal boundary for this business type"
          else null end)})
    end;
  (events($cpu)) as $cpuEvents |
  (events($allocations)) as $allocationEvents |
  (events($heapGc)) as $gcEvents |
  (events($gcSignals)) as $gcSignalEvents |
  (events($nativeMemory)) as $nativeEvents |
  (events($locks)) as $lockEvents |
  (events($safepoints)) as $safepointEvents |
  (events($jit)) as $jitEvents |
  (events($io)) as $ioEvents |
  (events($exceptions)) as $exceptionEvents |
  (events($oldObjects)) as $oldObjectEvents |
  (events($system)) as $systemEvents |
  (events($owner)) as $ownerEvents |
  (events($workload)) as $workloadEvents |
  (events($threads)) as $threadEvents |
  (terminalOperations($ownerEvents)) as $ownerTerminalOps |
  (terminalOperations([$workloadEvents[] |
    select(.type != "com.surprising.LinearPerpetualBusinessLatency")])) as $workloadTerminalOps |
  ($ownerTerminalOps + $workloadTerminalOps) as $terminalOps |
  (threadAllocationDelta($allocationEvents)) as $threadAllocatedBytes |
  ([$allocationEvents[] | select(.type != "jdk.ThreadAllocationStatistics")]) as $allAllocationSamples |
  (if ([$allAllocationSamples[] | select(.type == "jdk.ObjectAllocationSample")] | length) > 0
   then [$allAllocationSamples[] | select(.type == "jdk.ObjectAllocationSample")]
   else $allAllocationSamples end) as $allocationSamples |
  ([$allocationSamples[] | allocationWeightBytes] | add // 0) as $allocatedBytes |
  ([$allocationSamples[] | singleObjectBytes | select(. != null and . > 0)]) as $singleObjectSizes |
  (($cpuEvents + $allocationEvents + $gcEvents + $lockEvents + $ioEvents + $exceptionEvents
    + $threadEvents + $ownerEvents + $workloadEvents) | recordingSeconds(.)) as $recordingSeconds |
  (stateRows($cpuEvents; $lockEvents; $executionSamplePeriodNanos)) as $stateRows |
  ([$cpuEvents[] | select(.type == "jdk.ExecutionSample" or .type == "jdk.NativeMethodSample") |
    {source: eventSamplePeriodSource, nanos: eventSamplePeriodNanos($executionSamplePeriodNanos)}])
    as $samplePeriods |
  ([$stateRows[] | select(.state == "RUNNABLE") | .nanos] | add // 0) as $runnableNanos |
  ([$stateRows[] | select(.busySpin) | .nanos] | add // 0) as $busySpinNanos |
  {
    qualificationThresholds: {
      maxExceptions: $maxExceptions,
      maxOwnerSynchronousIoEvents: $maxOwnerSyncIoEvents,
      maxOwnerSynchronousIoBytes: $maxOwnerSyncIoBytes
    },
    terminalBusinessOperations: $terminalOps,
    terminalBusinessOperationSources: {
      ownerCommitEvents: $ownerTerminalOps,
      scaleOrSaturationEvents: $workloadTerminalOps,
      scaleOrSaturationParameters: [$workloadEvents[] |
        select(.type != "com.surprising.LinearPerpetualBusinessLatency") | {
        type, operationsPerInvocation: (.values.operationsPerInvocation // null),
        terminalBusinessOperations: (.values.terminalBusinessOperations // null)}]
    },
    topCpuWallMethods: top($cpuEvents; frame(.)),
    topCpuWallThreads: top($cpuEvents; thread(.)),
    topCpuWallStacks: top($cpuEvents; (.values.stackTrace.frames // [] | map(
      (.method.type.name // "unknown") + "." + (.method.name // "unknown")) | join(" <- "))),
    allocation: {
      recordingSeconds: $recordingSeconds,
      samples: ($allocationEvents | length),
      allocationEventSamples: ($allocationSamples | length),
      samplingMode: (if ([$allocationSamples[] |
        select(.type == "jdk.ObjectAllocationSample")] | length) > 0
        then "OBJECT_ALLOCATION_SAMPLE_WEIGHT"
        else "TLAB_WEIGHT" end),
      sampledBytes: $allocatedBytes,
      threadAllocatedBytes: $threadAllocatedBytes,
      allocatedBytes: $allocatedBytes,
      bytesPerSecond: ($allocatedBytes / $recordingSeconds),
      objectsPerSecond: null,
      bytesPerTerminalBusinessOperation:
        (if $terminalOps == 0 then null else $allocatedBytes / $terminalOps end),
      objectsPerTerminalBusinessOperation: null,
      objectCountSampling: {
        exactAvailable: false,
        status: "N/A",
        reason: "sampled/TLAB allocation events do not provide an exact allocated-object count"
      },
      maximumSingleObjectBytes:
        (if ($singleObjectSizes | length) == 0 then null else ($singleObjectSizes | max) end),
      maximumSingleObjectSampling:
        (if ($singleObjectSizes | length) > 0 then
          {status: "OBSERVED", reason: null}
        elif ($allocationSamples | length) > 0 then
          {status: "N/A", reason: "allocation events contain weights/TLAB sizes but no exact object size"}
        else
          {status: "N/A", reason: "no allocation sample event in recording"}
        end),
      inNewTlabSamples: ([$allocationEvents[] | select(.type == "jdk.ObjectAllocationInNewTLAB")] | length),
      outsideTlabSamples: ([$allocationEvents[] | select(.type == "jdk.ObjectAllocationOutsideTLAB")] | length),
      sampledBytesPerTerminalBusinessOperation:
        (if $terminalOps == 0 then null else $allocatedBytes / $terminalOps end),
      topSites: top($allocationSamples; frame(.)),
      topThreads: top($allocationSamples; thread(.)),
      topTypes: topAllocationTypes($allocationSamples)
    },
    heap: {
      observations: ([$gcEvents[] | select(.type == "jdk.GCHeapSummary")] | length),
      committedMaxBytes: ([$gcEvents[] | select(.type == "jdk.GCHeapSummary") |
        (.values.heapSpace.committedSize // .values.committedSize // 0)] | max // 0),
      usedMaxBytes: ([$gcEvents[] | select(.type == "jdk.GCHeapSummary") |
        (.values.heapUsed // .values.heapSpace.usedSize // 0)] | max // 0),
      committedSeries: [$gcEvents[] | select(.type == "jdk.GCHeapSummary") |
        {startTime: .values.startTime, when: .values.when,
         committedBytes: (.values.heapSpace.committedSize // .values.committedSize // 0)}],
      usedSeries: [$gcEvents[] | select(.type == "jdk.GCHeapSummary") |
        {startTime: .values.startTime, when: .values.when,
         usedBytes: (.values.heapUsed // .values.heapSpace.usedSize // 0)}],
      liveSetAfterGcSeries: [$gcEvents[] | select(.type == "jdk.GCHeapSummary") |
        select((.values.when // "") | test("after"; "i")) |
        {startTime: .values.startTime,
         usedBytes: (.values.heapUsed // .values.heapSpace.usedSize // 0)}]
    },
    gc: {
      events: ($gcEvents | length),
      collections: ([$gcEvents[] | select(.type == "jdk.GarbageCollection")] | length),
      byType: top($gcEvents; .type),
      causes: top([$gcEvents[] | select(.type == "jdk.GarbageCollection")];
        (.values.cause // "unknown")),
      totalGcNanos: ([$gcEvents[] | select(.type == "jdk.GarbageCollection") |
        (.values.duration // 0 | durationNanos)] | add // 0),
      totalGcTimeRatio: (([$gcEvents[] | select(.type == "jdk.GarbageCollection") |
        (.values.duration // 0 | durationNanos)] | add // 0) / ($recordingSeconds * 1000000000)),
      totalPauseNanos: ([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)] | add // 0),
      pauseP50Nanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 0.50),
      pauseP90Nanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 0.90),
      pauseP95Nanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 0.95),
      pauseP99Nanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 0.99),
      pauseP999Nanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 0.999),
      pauseMaxNanos: percentile([$gcEvents[] | select(.type == "jdk.GCPhasePause") |
        (.values.duration // 0 | durationNanos)]; 1.0),
      longestPhase: ([$gcEvents[] | select(.type | startswith("jdk.GCPhase")) |
        {type, name: (.values.name // "unknown"),
         durationNanos: (.values.duration // 0 | durationNanos)}] |
        sort_by(.durationNanos) | last // null),
      failureAndDegenerationSignals: [$gcSignalEvents[] | {type,
        thread: thread(.), durationNanos: (.values.duration // 0 | durationNanos), values: .values}],
      allocationStallCount: ([$gcSignalEvents[] |
        select(.type | test("AllocationStall|Allocation Stall"; "i"))] | length),
      failureOrDegenerationCount: ([$gcSignalEvents[] |
        select(.type | test("Failure|Failed|Degenerat"; "i"))] | length)
    },
    nativeMemory: {
      events: ($nativeEvents | length),
      categories: [$nativeEvents[] | {type, category: (.values.type // .values.name // "unknown"),
        reserved: (.values.reserved // 0), committed: (.values.committed // 0),
        count: (.values.count // 0), totalCapacity: (.values.totalCapacity // 0),
        memoryUsed: (.values.memoryUsed // 0)}]
    },
    locksAndParks: {events: ($lockEvents | length), rawThreadParkJsonMaterialized: false,
      aggregateArtifacts: ["contention-by-site.txt", "contention-by-thread.txt"],
      totalDurationNanos: ([$lockEvents[] | (.values.duration // 0 | durationNanos)] | add // 0),
      topMethods: top($lockEvents; frame(.)), topThreads: top($lockEvents; thread(.))},
    threads: {
      starts: ([$threadEvents[] | select(.type == "jdk.ThreadStart")] | length),
      ends: ([$threadEvents[] | select(.type == "jdk.ThreadEnd")] | length),
      peakActive: ([$threadEvents[] | select(.type == "jdk.JavaThreadStatistics") |
        (.values.peakCount // .values.activeCount // .values.activeThreads // 0)] | max // 0),
      peakDaemon: ([$threadEvents[] | select(.type == "jdk.JavaThreadStatistics") |
        (.values.daemonCount // .values.daemonThreads // 0)] | max // 0),
      lifecycleByThread: top($threadEvents; thread(.)),
      executionSamplePeriodNanos: $executionSamplePeriodNanos,
      runnableTimeInference: "event duration/period metadata when present; otherwise explicit recording-configured period",
      runnablePeriodValidation: {
        configuredNanos: $executionSamplePeriodNanos,
        eventMetadataCount: ([$samplePeriods[] | select(.source == "event-metadata")] | length),
        configuredFallbackCount: ([$samplePeriods[] |
          select(.source == "explicit-recording-configuration")] | length),
        nonPositiveCount: ([$samplePeriods[] | select(.nanos <= 0)] | length),
        metadataMismatchCount: ([$samplePeriods[] | select(.source == "event-metadata" and
          .nanos != $executionSamplePeriodNanos)] | length),
        status: (if all($samplePeriods[]; .nanos > 0) and
          all($samplePeriods[]; .source != "event-metadata" or .nanos == $executionSamplePeriodNanos)
          then "VALIDATED" else "INVALID" end)
      },
      byThreadStateTime: perThreadStates($stateRows),
      bySemanticRoleStateTime: perRoleStates($stateRows),
      requiredRoleCoverage: requiredRoleCoverage($stateRows),
      unclassified: {
        events: ([$stateRows[] | select(.role == "unclassified")] | length),
        observedNanos: ([$stateRows[] | select(.role == "unclassified") | .nanos] | add // 0),
        threads: ([$stateRows[] | select(.role == "unclassified") | .thread] | unique)
      },
      busySpinCpu: {
        sampledCpuNanos: $busySpinNanos,
        runnableSampledNanos: $runnableNanos,
        runnableSharePercent:
          (if $runnableNanos == 0 then 0 else $busySpinNanos * 100 / $runnableNanos end),
        threads: ([$stateRows[] | select(.busySpin)] | group_by(.thread) | map({
          thread: .[0].thread, role: .[0].role, samples: length,
          sampledCpuNanos: ([.[].nanos] | add // 0)}) | sort_by(-.sampledCpuNanos))
      },
      stateObservations: {
        runnable: ([$cpuEvents[] | select(.type == "jdk.ExecutionSample" or
          .type == "jdk.NativeMethodSample")] | length),
        blocked: ([$lockEvents[] | select(.type == "jdk.JavaMonitorEnter")] | length),
        waiting: ([$lockEvents[] | select(.type == "jdk.JavaMonitorWait")] | length),
        parked: "see bounded contention aggregate artifacts",
        sleeping: ([$lockEvents[] | select(.type == "jdk.ThreadSleep")] | length)
      }
    },
    safepoints: {
      events: ([$safepointEvents[] | select(.type | startswith("jdk.Safepoint"))] | length),
      reasons: top([$safepointEvents[] |
        select(.type == "jdk.SafepointBegin" or .type == "jdk.ExecuteVMOperation")];
        (.values.reason // .values.operation // .values.name // "unknown")),
      totalPauseNanos: ([$safepointEvents[] | select(.type == "jdk.SafepointEnd") |
        (.values.duration // 0 | durationNanos)] | add // 0),
      maxPauseNanos: percentile([$safepointEvents[] | select(.type == "jdk.SafepointEnd") |
        (.values.duration // 0 | durationNanos)]; 1.0),
      timeToSafepoint: {
        count: ([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization")] | length),
        totalNanos: ([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)] | add // 0),
        p50Nanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 0.50),
        p90Nanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 0.90),
        p95Nanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 0.95),
        p99Nanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 0.99),
        p999Nanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 0.999),
        maxNanos: percentile([$safepointEvents[] |
          select(.type == "jdk.SafepointStateSynchronization") |
          (.values.duration // 0 | durationNanos)]; 1.0)
      },
      byType: durationTop([$safepointEvents[] |
        select(.type | startswith("jdk.Safepoint"))]; .type)
    },
    vmOperations: {
      count: ([$safepointEvents[] | select(.type == "jdk.ExecuteVMOperation")] | length),
      totalDurationNanos: ([$safepointEvents[] | select(.type == "jdk.ExecuteVMOperation") |
        (.values.duration // 0 | durationNanos)] | add // 0),
      topOperations: durationTop([$safepointEvents[] |
        select(.type == "jdk.ExecuteVMOperation")];
        (.values.operation // .values.name // "unknown")),
      longest: ([$safepointEvents[] | select(.type == "jdk.ExecuteVMOperation") |
        {operation: (.values.operation // .values.name // "unknown"), thread: thread(.),
         durationNanos: (.values.duration // 0 | durationNanos)}] |
        sort_by(.durationNanos) | last // null),
      topThreads: top([$safepointEvents[] | select(.type == "jdk.ExecuteVMOperation")]; thread(.))
    },
    jitAndCode: {
      events: ($jitEvents | length),
      byType: top($jitEvents; .type),
      telemetryCoverage: [
        telemetryCoverage("code-cache"; $codeCacheConfigured; $codeCacheSupported;
          ([$jitEvents[] | select(.type == "jdk.CodeCacheStatistics")] | length)),
        telemetryCoverage("metaspace"; $metaspaceConfigured; $metaspaceSupported;
          ([$jitEvents[] | select(.type == "jdk.MetaspaceSummary" or
            .type == "jdk.MetaspaceGCThreshold")] | length)),
        telemetryCoverage("deoptimization"; $deoptConfigured; $deoptSupported;
          ([$jitEvents[] | select(.type == "jdk.Deoptimization")] | length)),
        telemetryCoverage("class-load-unload"; $classConfigured; $classSupported;
          ([$jitEvents[] | select(.type == "jdk.ClassLoad" or .type == "jdk.ClassUnload")] | length))
      ],
      compilations: {
        count: ([$jitEvents[] | select(.type == "jdk.Compilation")] | length),
        totalDurationNanos: ([$jitEvents[] | select(.type == "jdk.Compilation") |
          (.values.duration // 0 | durationNanos)] | add // 0),
        topMethods: durationTop([$jitEvents[] | select(.type == "jdk.Compilation")];
          compilationMethod),
        longest: ([$jitEvents[] | select(.type == "jdk.Compilation") |
          {method: compilationMethod, compiler: (.values.compiler // .values.compileLevel // "unknown"),
           durationNanos: (.values.duration // 0 | durationNanos)}] |
          sort_by(.durationNanos) | last // null)
      },
      compilationFailures: {
        count: ([$jitEvents[] | select(.type == "jdk.CompilationFailure")] | length),
        details: [$jitEvents[] | select(.type == "jdk.CompilationFailure") |
          {message: (.values.failureMessage // .values.message // "unknown"),
           compileId: (.values.compileId // null), method: compilationMethod}]
      },
      codeCache: {
        observations: ([$jitEvents[] | select(.type == "jdk.CodeCacheStatistics")] | length),
        maxUsedBytes: ([$jitEvents[] | select(.type == "jdk.CodeCacheStatistics") |
          (.values.usedSize // .values.used // 0)] | max // 0),
        maxCommittedBytes: ([$jitEvents[] | select(.type == "jdk.CodeCacheStatistics") |
          (.values.committedSize // .values.committed // 0)] | max // 0),
        series: [$jitEvents[] | select(.type == "jdk.CodeCacheStatistics") |
          {startTime: .values.startTime, name: (.values.codeBlobType // .values.name // "unknown"),
           usedBytes: (.values.usedSize // .values.used // 0),
           committedBytes: (.values.committedSize // .values.committed // 0)}]
      },
      deoptimizations: {
        count: ([$jitEvents[] | select(.type == "jdk.Deoptimization")] | length),
        details: [$jitEvents[] | select(.type == "jdk.Deoptimization") |
          {reason: (.values.reason // "unknown"), action: (.values.action // "unknown"),
           method: compilationMethod}]
      },
      classLoading: {
        loads: ([$jitEvents[] | select(.type == "jdk.ClassLoad")] | length),
        unloads: ([$jitEvents[] | select(.type == "jdk.ClassUnload")] | length)
      },
      metaspace: {
        observations: ([$jitEvents[] | select(.type == "jdk.MetaspaceSummary")] | length),
        maxUsedBytes: ([$jitEvents[] | select(.type == "jdk.MetaspaceSummary") |
          (.values.metaspace.used // .values.metaspaceUsed // .values.used // 0)] | max // 0),
        maxCommittedBytes: ([$jitEvents[] | select(.type == "jdk.MetaspaceSummary") |
          (.values.metaspace.committed // .values.metaspaceCommitted // .values.committed // 0)] |
          max // 0),
        thresholdEvents: ([$jitEvents[] | select(.type == "jdk.MetaspaceGCThreshold")] | length),
        series: [$jitEvents[] | select(.type == "jdk.MetaspaceSummary") |
          {startTime: .values.startTime, when: (.values.when // "unknown"),
           usedBytes: (.values.metaspace.used // .values.metaspaceUsed // .values.used // 0),
           committedBytes: (.values.metaspace.committed // .values.metaspaceCommitted //
             .values.committed // 0)}]
      }
    },
    io: {events: ($ioEvents | length), healthyZero: (($ioEvents | length) == 0),
      bytes: ([$ioEvents[] | ioBytes] | add // 0),
      totalDurationNanos: ([$ioEvents[] | (.values.duration // 0 | durationNanos)] | add // 0),
      byType: top($ioEvents; .type), topMethods: top($ioEvents; frame(.))},
    exceptions: {count: ($exceptionEvents | length), healthyZero: (($exceptionEvents | length) == 0),
      byType: top($exceptionEvents; (.values.thrownClass.name // .values.message // .type)),
      topThrowSites: top($exceptionEvents; frame(.)), topThreads: top($exceptionEvents; thread(.))},
    oldObjectEvidence:
      (if $oldObjectEscalation != true then
        {status: "DISABLED", escalation: false, configured: $oldObjectConfigured,
         supported: $oldObjectSupported, samples: ($oldObjectEvents | length),
         reason: "OldObject escalation was not requested"}
      elif $oldObjectConfigured != true then
        {status: "UNKNOWN", escalation: true, configured: false,
         supported: $oldObjectSupported, samples: ($oldObjectEvents | length),
         reason: "effective JFC does not enable jdk.OldObjectSample"}
      elif $oldObjectSupported != true then
        {status: "N/A", escalation: true, configured: true, supported: false, samples: 0,
         reason: "jdk.OldObjectSample is absent from JVM recording metadata"}
      elif ($oldObjectEvents | length) > 0 then
        {status: "OBSERVED", escalation: true, configured: true, supported: true,
         samples: ($oldObjectEvents | length), reason: null,
         topTypes: top($oldObjectEvents; allocationType),
         topSites: top($oldObjectEvents; frame(.))}
      else
        {status: "MISSING", escalation: true, configured: true, supported: true, samples: 0,
         reason: "enabled and supported OldObject escalation emitted no sample"}
      end),
    ownerSynchronousIo: {
      events: ([$ioEvents[] | select(eventRole == "owner")] | length),
      bytes: ([$ioEvents[] | select(eventRole == "owner") | ioBytes] | add // 0),
      totalDurationNanos: ([$ioEvents[] | select(eventRole == "owner") |
        (.values.duration // 0 | durationNanos)] | add // 0),
      topMethods: top([$ioEvents[] | select(eventRole == "owner")]; frame(.)),
      threads: top([$ioEvents[] | select(eventRole == "owner")]; thread(.))
    },
    systemAndContainer: {events: ($systemEvents | length), byType: top($systemEvents; .type),
      observations: [$systemEvents[] | {type, values: .values}]},
    ownerLatencyByBusinessType: ownerLatency($ownerEvents)
    ,workloadLatencyByBusinessType: ownerLatency($workloadEvents)
    ,workloadLatencyQualification:
      latencyQualification($workloadLatencyContract; ownerLatency($workloadEvents))
  }' > "${OUTPUT_DIR}/aggregate.json"

jq -e '.terminalBusinessOperations > 0
  and .allocation.bytesPerSecond >= 0
  and .allocation.objectsPerSecond == null
  and .allocation.bytesPerTerminalBusinessOperation != null
  and .allocation.objectsPerTerminalBusinessOperation == null
  and .allocation.objectCountSampling.exactAvailable == false
  and .allocation.objectCountSampling.status == "N/A"
  and (.allocation.objectCountSampling.reason | length) > 0
  and ((.allocation.maximumSingleObjectBytes != null and .allocation.maximumSingleObjectBytes > 0
        and .allocation.maximumSingleObjectSampling.status == "OBSERVED")
    or (.allocation.maximumSingleObjectBytes == null
        and .allocation.maximumSingleObjectSampling.status == "N/A"
        and (.allocation.maximumSingleObjectSampling.reason | length) > 0))
  and (.allocation.topTypes | type) == "array"
  and .heap.observations > 0 and .heap.committedMaxBytes > 0 and .heap.usedMaxBytes >= 0
  and .gc.totalGcTimeRatio >= 0
  and .gc.pauseP999Nanos >= .gc.pauseP99Nanos
  and (.gc.longestPhase == null or .gc.longestPhase.durationNanos >= 0)
  and .threads.peakActive > 0
  and (.threads.byThreadStateTime | length) > 0
  and (.threads.bySemanticRoleStateTime | length) > 0
  and .threads.runnablePeriodValidation.status == "VALIDATED"
  and .threads.runnablePeriodValidation.nonPositiveCount == 0
  and .threads.runnablePeriodValidation.metadataMismatchCount == 0
  and (.threads.requiredRoleCoverage | length) == 12
  and ([.threads.requiredRoleCoverage[].role] ==
    ["owner", "matcher", "risk", "snapshot", "projection", "core-fact/exporter",
     "Aeron", "Kafka", "peripheral", "lane", "GC", "compiler"])
  and all(.threads.requiredRoleCoverage[];
    (.status == "OBSERVED" and .observedNanos > 0 and .reason == null) or
    (.status == "N/A" and .observedNanos == 0 and (.reason | length) > 0))
  and .threads.unclassified.events == 0
  and .threads.unclassified.observedNanos == 0
  and .threads.busySpinCpu.sampledCpuNanos >= 0
  and .safepoints.timeToSafepoint.totalNanos >= 0
  and .vmOperations.count >= 0 and .vmOperations.totalDurationNanos >= 0
  and (.vmOperations.count == 0 or .vmOperations.longest.durationNanos >= 0)
  and .jitAndCode.compilations.count >= 0
  and .jitAndCode.compilations.totalDurationNanos >= 0
  and (.jitAndCode.compilations.count == 0 or
    .jitAndCode.compilations.longest.durationNanos >= 0)
  and .jitAndCode.compilationFailures.count >= 0
  and (.jitAndCode.telemetryCoverage | length) == 4
  and ([.jitAndCode.telemetryCoverage[].family] ==
    ["code-cache", "metaspace", "deoptimization", "class-load-unload"])
  and all(.jitAndCode.telemetryCoverage[];
    (.status == "OBSERVED" and .configured == true and .supported == true
      and .events > 0 and .reason == null) or
    (.status == "N/A" and .configured == true and .supported == false
      and .events == 0 and (.reason | length) > 0))
  and ((.oldObjectEvidence.escalation == false and .oldObjectEvidence.status == "DISABLED") or
    (.oldObjectEvidence.escalation == true and
      ((.oldObjectEvidence.status == "OBSERVED" and .oldObjectEvidence.configured == true
        and .oldObjectEvidence.supported == true and .oldObjectEvidence.samples > 0) or
       (.oldObjectEvidence.status == "N/A" and .oldObjectEvidence.configured == true
        and .oldObjectEvidence.supported == false and .oldObjectEvidence.samples == 0
        and (.oldObjectEvidence.reason | length) > 0))))' "${OUTPUT_DIR}/aggregate.json" > /dev/null

if ! jq -e --argjson maximum "${MAX_EXCEPTIONS}" \
  '.exceptions.count <= $maximum' "${OUTPUT_DIR}/aggregate.json" > /dev/null; then
  echo "JFR exception threshold exceeded: maximum=${MAX_EXCEPTIONS}" >&2
  exit 6
fi
if ! jq -e --argjson maximumEvents "${MAX_OWNER_SYNC_IO_EVENTS}" \
  --argjson maximumBytes "${MAX_OWNER_SYNC_IO_BYTES}" \
  '.ownerSynchronousIo.events <= $maximumEvents
    and .ownerSynchronousIo.bytes <= $maximumBytes' \
  "${OUTPUT_DIR}/aggregate.json" > /dev/null; then
  echo "owner synchronous I/O threshold exceeded: events=${MAX_OWNER_SYNC_IO_EVENTS}, bytes=${MAX_OWNER_SYNC_IO_BYTES}" >&2
  exit 7
fi

if [[ "${OWNER_MEASUREMENTS}" == "true" ]]; then
  jq -e '.terminalBusinessOperations > 0
    and (.ownerLatencyByBusinessType | length) > 0
    and all(.ownerLatencyByBusinessType[];
      .operationsPerInvocation == 16384 and .terminalBusinessOperations > 0
      and .loadModel == "OPEN_LOOP_CONSTANT_ARRIVAL"
      and .coordinatedOmissionCorrected == true)' \
    "${OUTPUT_DIR}/aggregate.json" > /dev/null
fi
if [[ -n "${WORKLOAD_LATENCY_CONTRACT}" ]]; then
  jq -e '.workloadLatencyQualification | length == 12 and
    all(.[]; .status == .expectedStatus and
      ((.status == "EXERCISED" and .eventCount == 1 and .latency != null and .reason == null) or
       (.status == "NOT_EXERCISED" and .eventCount == 0 and .latency == null
        and (.reason | length) > 0)))' \
    "${OUTPUT_DIR}/aggregate.json" > /dev/null
  jq -e '.workloadLatencyByBusinessType | length > 0 and all(.[ ];
      .loadModel == "OPEN_LOOP_CONSTANT_ARRIVAL" and .coordinatedOmissionCorrected == true and
      .operationsPerInvocation > 0 and .targetOperationsPerSecond == 100000 and
      .classificationSource == "EXHAUSTIVE_CORE_MESSAGE_TYPE_SWITCH" and
      .quantileAggregation == "MERGED_LOG2_HISTOGRAM_COUNTS" and
      .scheduledBusinessOperations == .terminalBusinessOperations and
      .terminalBusinessOperations > 0 and .terminalCoreMessages > 0 and
      .latencySamples == .terminalCoreMessages and
      (.entryAcceptedHistogramCounts | add) == .latencySamples and
      (.acceptedTerminalHistogramCounts | add) == .latencySamples and
      (.entryTerminalHistogramCounts | add) == .latencySamples and
      .histogramLowestNanos > 0 and .histogramHighestNanos >= .timeoutNanos and
      .timeoutNanos > 0 and .latencyUnit == "NANOSECONDS" and
      .entryAcceptedP50Nanos <= .entryAcceptedP90Nanos and
      .entryAcceptedP90Nanos <= .entryAcceptedP95Nanos and
      .entryAcceptedP95Nanos <= .entryAcceptedP99Nanos and
      .entryAcceptedP99Nanos <= .entryAcceptedP999Nanos and
      .entryAcceptedP999Nanos <= .entryAcceptedMaxNanos and
      .acceptedTerminalP50Nanos <= .acceptedTerminalP90Nanos and
      .acceptedTerminalP90Nanos <= .acceptedTerminalP95Nanos and
      .acceptedTerminalP95Nanos <= .acceptedTerminalP99Nanos and
      .acceptedTerminalP99Nanos <= .acceptedTerminalP999Nanos and
      .acceptedTerminalP999Nanos <= .acceptedTerminalMaxNanos and
      .entryTerminalP50Nanos <= .entryTerminalP90Nanos and
      .entryTerminalP90Nanos <= .entryTerminalP95Nanos and
      .entryTerminalP95Nanos <= .entryTerminalP99Nanos and
      .entryTerminalP99Nanos <= .entryTerminalP999Nanos and
      .entryTerminalP999Nanos <= .entryTerminalMaxNanos)' \
    "${OUTPUT_DIR}/aggregate.json" > /dev/null
fi
NMT_PREFIX="${RECORDING%.jfr}-nmt"
if [[ ! -s "${NMT_PREFIX}-baseline.txt" || ! -s "${NMT_PREFIX}-summary.diff.txt" ]]; then
  echo "recording is missing NMT baseline/summary.diff evidence" >&2
  exit 3
fi

if jq -e '[.recording.events[]? | select(.type == "jdk.ContainerCPUThrottling") |
  (.values.cpuThrottledTime // 0) |
  select(. != 0 and . != "PT0S" and . != "PT0.000000000S")] | length > 0' \
  "${OUTPUT_DIR}/system-container.json" > /dev/null; then
  echo "container CPU throttling detected; recording is invalid" >&2
  exit 5
fi
if jq -e '[.recording.events[]? | select(.type == "jdk.SwapSpace") |
  select((.values.totalSize // 0) > (.values.freeSize // .values.totalSize // 0))] | length > 0' \
  "${OUTPUT_DIR}/system-container.json" > /dev/null; then
  echo "swap usage detected; recording is invalid" >&2
  exit 5
fi

JFR_SETTINGS_SHA256="$(shasum -a 256 "${JFR_SETTINGS_FILE}" | awk '{print $1}')"
printf 'recording=%s\nsummary=%s\nmetadata=%s\naggregate=%s\njfrSettings=%s\njfrSettingsSha256=%s\noldObjectEscalation=%s\npathToGcRoots=%s\noldObjectStatus=%s\nnmtBaseline=%s\nnmtSummaryDiff=%s\nexecutionSamplePeriodNanos=%s\nmaxExceptions=%s\nmaxOwnerSyncIoEvents=%s\nmaxOwnerSyncIoBytes=%s\noutput=%s\n' \
  "${RECORDING}" "${SUMMARY}" "${METADATA}" "${OUTPUT_DIR}/aggregate.json" \
  "${JFR_SETTINGS_FILE}" "${JFR_SETTINGS_SHA256}" \
  "${OLD_OBJECT_ESCALATION}" "${OLD_OBJECT_ESCALATION}" \
  "$(jq -r '.oldObjectEvidence.status' "${OUTPUT_DIR}/aggregate.json")" \
  "${RECORDING%.jfr}-nmt-baseline.txt" "${RECORDING%.jfr}-nmt-summary.diff.txt" \
  "${EXECUTION_SAMPLE_PERIOD_NANOS}" "${MAX_EXCEPTIONS}" "${MAX_OWNER_SYNC_IO_EVENTS}" \
  "${MAX_OWNER_SYNC_IO_BYTES}" "${OUTPUT_DIR}" \
  > "${OUTPUT_DIR}/analysis-manifest.txt"
echo "Owner-commit JFR analysis: ${OUTPUT_DIR}"
