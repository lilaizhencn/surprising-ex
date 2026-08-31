#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MODULE="${ROOT}/surprising-aeron-core/surprising-aeron-benchmarks"
PROTOCOL="${ROOT}/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java"
RECORDER="${MODULE}/src/main/java/com/surprising/aeron/service/OpenLoopBusinessLatencyRecorder.java"
ANALYZER="${MODULE}/bin/analyze-owner-commit-jfr.sh"
QUALIFIER="${MODULE}/bin/qualify-linear-perpetual-scale.sh"
OUTPUT="${1:-${ROOT}/.omo/evidence/round8-static}"
mkdir -p "${OUTPUT}"
TEMP="$(mktemp -d)"
trap 'rm -rf "${TEMP}"' EXIT

sed -n '/public enum CoreMessageType {/,/private final int wireCode/p' "${PROTOCOL}" \
  | sed -nE 's/^[[:space:]]*([A-Z][A-Z0-9_]*)\(.*/\1/p' | sort > "${TEMP}/enum.txt"
awk '
  /return switch \(Objects.requireNonNull/ { inside=1; next }
  inside {
    buffer=buffer " " $0
    if (index(buffer, "->") != 0) {
      sub(/^.*case[[:space:]]+/, "", buffer)
      sub(/[[:space:]]+->[[:space:]].*$/, "", buffer)
      gsub(/,/, " ", buffer)
      count=split(buffer, values, /[[:space:]]+/)
      for (i=1; i<=count; i++) if (values[i] != "") print values[i]
      buffer=""
    }
    if ($0 ~ /};/) exit
  }
' "${RECORDER}" | sort > "${TEMP}/switch.txt"
diff -u "${TEMP}/enum.txt" "${TEMP}/switch.txt" > "${TEMP}/exhaustive-switch.diff"
[[ ! -s "${TEMP}/exhaustive-switch.diff" ]]
printf 'PASS every CoreMessageType appears exactly once in the exhaustive switch\n' \
  > "${OUTPUT}/exhaustive-switch.txt"
! sed -n '/return switch (Objects.requireNonNull/,/};/p' "${RECORDER}" | grep -q 'default'
! sed -n '/static BusinessType classify/,/^        }/p' "${RECORDER}" | grep -Eq '\.name\(|contains\(|startsWith\(|endsWith\('
grep -q 'Objects.requireNonNull(type, "message type")' "${RECORDER}"
printf 'PASS null/unknown classification is rejected and future enum constants break exhaustive compilation\n' \
  > "${OUTPUT}/unknown-type-reject.txt"

EXPECTED_TYPES='ADL AMEND_ORDER CANCEL_ORDER FUNDING LIQUIDATION ORDER_BATCH PLACE_ORDER RISK_SCAN SETTLEMENT SNAPSHOT_RECOVERY TAKER_FILL TRIGGER_ORDER'
ACTUAL_TYPES="$(sed -n '/enum BusinessType {/,/;/p' "${RECORDER}" | tail -n +2 \
  | tr ',;' '\n\n' | sed 's/[[:space:]]//g' | sed '/^$/d' | sort | tr '\n' ' ' | sed 's/ $//')"
[[ "${ACTUAL_TYPES}" == "${EXPECTED_TYPES}" ]]
[[ "$(grep -c 'REQUIRE_WORKLOAD_LATENCY_CONTRACT=' "${QUALIFIER}")" -eq 2 ]]
while IFS= read -r contract; do
  labels="$(printf '%s\n' "${contract}" | tr ',' '\n' | cut -d= -f1 | sort | tr '\n' ' ' | sed 's/ $//')"
  [[ "${labels}" == "${EXPECTED_TYPES}" ]]
  ! printf '%s\n' "${contract}" | grep -Eq 'ORDER=|RISK='
done < <(sed -nE "s/.*REQUIRE_WORKLOAD_LATENCY_CONTRACT='([^']+)'.*/\1/p" "${QUALIFIER}")

grep -q 'scheduledBusinessOperations = Math.addExact(scheduledBusinessOperations, operationWeight)' "${RECORDER}"
grep -q 'scheduledBusinessOperations == .terminalBusinessOperations' "${ANALYZER}"
jq -n '
  [4,1,3] as $weights |
  reduce $weights[] as $weight ({starts: [], scheduled: 0, terminal: 0, messages: 0};
    .starts += [.scheduled] | .scheduled += $weight | .terminal += $weight | .messages += 1) |
  select(.starts == [0,4,5] and .scheduled == 8 and .terminal == 8 and .messages == 3)
' > "${OUTPUT}/batch-weight.json"
[[ -s "${OUTPUT}/batch-weight.json" ]]

grep -q 'MERGED_LOG2_HISTOGRAM_COUNTS' "${ANALYZER}"
! grep -q 'median-of-equal-sized-invocation-quantiles' "${ANALYZER}"
jq -n '
  def pow2($n): reduce range(0; $n) as $ignored (1; . * 2);
  def hp($hist; $fraction):
    ($hist | add) as $total | ($total * $fraction | ceil) as $target |
    (reduce range(0; 64) as $i ({sum: 0, value: null};
      if .value == null then .sum += $hist[$i] |
        if .sum >= $target then .value = pow2($i) else . end else . end) | .value);
  ([range(0;64)|0] | .[0]=1) as $small |
  ([range(0;64)|0] | .[10]=100) as $large |
  ([range(0;64) as $i | $small[$i] + $large[$i]]) as $merged |
  {medianOfInvocationP50: 1, mergedSampleP50: hp($merged; .50), samples: ($merged|add)} |
  select(.medianOfInvocationP50 == 1 and .mergedSampleP50 == 1024 and .samples == 101)
' > "${OUTPUT}/quantile-aggregation-counterexample.json"
[[ -s "${OUTPUT}/quantile-aggregation-counterexample.json" ]]

grep -q 'classificationSource = "EXHAUSTIVE_CORE_MESSAGE_TYPE_SWITCH"' "${RECORDER}"
grep -q '.classificationSource == "EXHAUSTIVE_CORE_MESSAGE_TYPE_SWITCH"' "${ANALYZER}"
grep -q 'event.businessType = type.name()' "${RECORDER}"
printf 'PASS emitted event businessType is the exact exhaustive classifier enum name\n' \
  > "${OUTPUT}/event-classification-consistency.txt"
bash -n "${ANALYZER}" "${QUALIFIER}" "$0"
git -C "${ROOT}" diff --check -- "${MODULE}"
printf 'PASS exhaustive business latency classification, weighted scheduling, merged histograms\n' \
  > "${OUTPUT}/static-verification.txt"
