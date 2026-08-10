#!/usr/bin/env bash
set -euo pipefail

EVIDENCE_DIR="${EVIDENCE_DIR:?EVIDENCE_DIR is required}"
STOP_FILE="${STOP_FILE:?STOP_FILE is required}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-2}"
TEST_ACTUATOR_PORTS="${TEST_ACTUATOR_PORTS:-9080 9082 9083 9084 9085 9086 9087 9088 9089 9090 9091 9094 9096}"

[[ "${SAMPLE_SECONDS}" =~ ^[1-9][0-9]*$ ]] || { echo "SAMPLE_SECONDS must be positive" >&2; exit 1; }
mkdir -p "${EVIDENCE_DIR}"
PROCESS_FILE="${EVIDENCE_DIR}/process.tsv"
ACTUATOR_FILE="${EVIDENCE_DIR}/actuator.tsv"
SUMMARY_FILE="${EVIDENCE_DIR}/resource-summary.env"
printf 'timestamp_ms\tpid\tcpu_pct\trss_kb\tcommand\n' >"${PROCESS_FILE}"
printf 'timestamp_ms\tport\tprocess_cpu\theap_used_bytes\theap_max_bytes\tgc_pause_max_seconds\tgc_pause_sum_seconds\tgc_pause_p99_seconds\ttomcat_threads_current\ttomcat_threads_busy\ttomcat_connections_current\n' >"${ACTUATOR_FILE}"

sample_processes() {
  ps -axo pid=,pcpu=,rss=,command= | awk '/surprising-.*provider|surprising-.*\.jar/ {pid=$1; cpu=$2; rss=$3; $1=""; $2=""; $3=""; sub(/^[[:space:]]+/, ""); print pid "\t" cpu "\t" rss "\t" $0}'
}

metric() {
  local content="$1" name="$2"
  awk -v name="${name}" '$1 ~ ("^" name "(\\{|$)") {print $2; exit}' <<<"${content}"
}

metric_sum() {
  local content="$1" name="$2"
  awk -v name="${name}" '$1 ~ ("^" name "(\\{|$)") {sum += $2; found=1} END {if (found) print sum}' <<<"${content}"
}

metric_max() {
  local content="$1" name="$2"
  awk -v name="${name}" '$1 ~ ("^" name "(\\{|$)") {if (!found || $2 > max) max=$2; found=1} END {if (found) print max}' <<<"${content}"
}

metric_histogram_p99() {
  python3 -c 'import re, sys
buckets = {}
for line in sys.stdin:
    fields = line.split()
    if not fields or not fields[0].startswith("jvm_gc_pause_seconds_bucket") or len(fields) < 2:
        continue
    match = re.search(r"le=\"([^\"]+)\"", fields[0])
    if not match or match.group(1) == "+Inf":
        continue
    try:
        buckets[float(match.group(1))] = buckets.get(float(match.group(1)), 0.0) + float(fields[1])
    except ValueError:
        pass
if buckets:
    total = max(buckets.values())
    target = total * 0.99
    cumulative = 0.0
    for upper, value in sorted(buckets.items()):
        cumulative = value
        if cumulative >= target:
            print(upper)
            break' <<<"$1"
}

sample_once() {
  local timestamp port content process_cpu heap_used heap_max gc_max gc_sum gc_p99 tomcat_current tomcat_busy tomcat_connections
  timestamp="$(date +%s%3N)"
  while IFS=$'\t' read -r pid cpu rss command; do
    [[ -n "${pid}" ]] || continue
    printf '%s\t%s\t%s\t%s\t%s\n' "${timestamp}" "${pid}" "${cpu}" "${rss}" "${command}" >>"${PROCESS_FILE}"
  done < <(sample_processes)
  for port in ${TEST_ACTUATOR_PORTS}; do
    content="$(curl --connect-timeout 1 --max-time 2 -fsS "http://localhost:${port}/actuator/prometheus" 2>/dev/null || true)"
    [[ -n "${content}" ]] || continue
    process_cpu="$(metric "${content}" process_cpu_usage)"
    heap_used="$(awk '$1 ~ /^jvm_memory_used_bytes/ && $1 ~ /area="heap"/ {sum += $2} END {if (sum != "") print sum}' <<<"${content}")"
    heap_max="$(awk '$1 ~ /^jvm_memory_max_bytes/ && $1 ~ /area="heap"/ {sum += $2} END {if (sum != "") print sum}' <<<"${content}")"
    gc_max="$(metric_max "${content}" jvm_gc_pause_seconds_max)"
    gc_sum="$(metric_sum "${content}" jvm_gc_pause_seconds_sum)"
    gc_p99="$(metric_histogram_p99 "${content}")"
    tomcat_current="$(metric_max "${content}" tomcat_threads_current_threads)"
    tomcat_busy="$(metric_max "${content}" tomcat_threads_busy_threads)"
    tomcat_connections="$(metric_max "${content}" tomcat_connections_current_connections)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${timestamp}" "${port}" "${process_cpu:-}" "${heap_used:-}" "${heap_max:-}" "${gc_max:-}" "${gc_sum:-}" "${gc_p99:-}" "${tomcat_current:-}" "${tomcat_busy:-}" "${tomcat_connections:-}" >>"${ACTUATOR_FILE}"
  done
}

while [[ ! -e "${STOP_FILE}" ]]; do
  sample_once
  sleep "${SAMPLE_SECONDS}"
done

awk -F '\t' '
  NR > 1 && $3 != "" {if ($3+0 > max_cpu) max_cpu=$3+0; samples++}
  NR > 1 && $4 != "" {if (first_rss == "") first_rss=$4+0; last_rss=$4+0; if ($4+0 > max_rss) max_rss=$4+0}
  END {printf "process_samples=%d\nmax_process_cpu_pct=%.3f\nfirst_rss_kb=%d\nlast_rss_kb=%d\nmax_rss_kb=%d\n", samples+0, max_cpu+0, first_rss+0, last_rss+0, max_rss+0}
' "${PROCESS_FILE}" >"${SUMMARY_FILE}"

awk -F '\t' '
  NR > 1 && $3 != "" {if ($3+0 > max_cpu) max_cpu=$3+0; cpu_samples++}
  NR > 1 && $6 != "" {if ($6+0 > max_gc) max_gc=$6+0; gc_samples++}
  NR > 1 && $4 != "" && $5 != "" && $5+0 > 0 {ratio=$4/$5; if (ratio > max_heap_ratio) max_heap_ratio=ratio; heap_samples++}
  NR > 1 && $8 != "" {if ($8+0 > max_gc_p99) max_gc_p99=$8+0; gc_p99_samples++}
  NR > 1 && $9 != "" {if ($9+0 > max_tomcat) max_tomcat=$9+0; tomcat_samples++}
  NR > 1 && $10 != "" {if ($10+0 > max_tomcat_busy) max_tomcat_busy=$10+0}
  NR > 1 && $11 != "" {if ($11+0 > max_tomcat_connections) max_tomcat_connections=$11+0}
  END {printf "actuator_cpu_samples=%d\nmax_actuator_cpu_ratio=%.6f\ngc_samples=%d\nmax_gc_pause_seconds=%.6f\ngc_p99_samples=%d\nmax_gc_pause_p99_seconds=%.6f\nheap_samples=%d\nmax_heap_used_ratio=%.6f\ntomcat_samples=%d\nmax_tomcat_threads_current=%.3f\nmax_tomcat_threads_busy=%.3f\nmax_tomcat_connections_current=%.3f\n", cpu_samples+0, max_cpu+0, gc_samples+0, max_gc+0, gc_p99_samples+0, max_gc_p99+0, heap_samples+0, max_heap_ratio+0, tomcat_samples+0, max_tomcat+0, max_tomcat_busy+0, max_tomcat_connections+0}
' "${ACTUATOR_FILE}" >>"${SUMMARY_FILE}"

echo "resource monitor complete: ${SUMMARY_FILE}"
