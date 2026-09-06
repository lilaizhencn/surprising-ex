#!/usr/bin/env bash
# Diagnostic collection, separate from unprofiled throughput. Outputs stay outside Git.
set -euo pipefail
if (( $# != 3 )); then echo 'usage: capture.sh PID SECONDS /absolute/new/output' >&2; exit 2; fi
PID=$1; SECONDS_TO_CAPTURE=$2; OUT=$3
[[ "$PID" =~ ^[0-9]+$ && "$SECONDS_TO_CAPTURE" =~ ^[0-9]+$ ]] || exit 2
(( SECONDS_TO_CAPTURE >= 10 && SECONDS_TO_CAPTURE <= 3600 )) || exit 2
: "${JAVA_HOME:?Set JAVA_HOME to HotSpot JDK 25}"
: "${VALIDATION_RECORD:?Lock the performance standard and scenario in PERFORMANCE_VALIDATION.md first}"
[[ "$OUT" == /* && ! -e "$OUT" ]] || exit 2
mkdir -m 700 -p "$OUT"
printf '%s\n' "$VALIDATION_RECORD" > "$OUT/validation-record.txt"
"$JAVA_HOME/bin/jcmd" "$PID" VM.version > "$OUT/vm-version.txt"
"$JAVA_HOME/bin/jcmd" "$PID" VM.command_line > "$OUT/vm-command-line.txt"
"$JAVA_HOME/bin/jcmd" "$PID" VM.native_memory baseline > "$OUT/nmt-baseline.txt"
"$JAVA_HOME/bin/jfr" configure --input "$JAVA_HOME/lib/jfr/profile.jfc" --output "$OUT/profile.jfc" \
  jdk.ObjectAllocationInNewTLAB#enabled=true jdk.ObjectAllocationOutsideTLAB#enabled=true \
  jdk.ThreadAllocationStatistics#enabled=true jdk.DirectBufferStatistics#enabled=true
"$JAVA_HOME/bin/jcmd" "$PID" JFR.start name=aws-validation settings="$OUT/profile.jfc" \
  duration="${SECONDS_TO_CAPTURE}s" filename="$OUT/recording.jfr" > "$OUT/jfr-start.txt"
uname -a > "$OUT/os.txt"
free -b > "$OUT/memory-before.txt"
cat /proc/vmstat > "$OUT/vmstat-before.txt"
cat /proc/"$PID"/status > "$OUT/process-before.txt"
if [[ -r /sys/fs/cgroup/cpu.stat ]]; then cat /sys/fs/cgroup/cpu.stat > "$OUT/cgroup-before.txt"; fi
vmstat 1 "$SECONDS_TO_CAPTURE" > "$OUT/vmstat.log" & P1=$!
mpstat -P ALL 1 "$SECONDS_TO_CAPTURE" > "$OUT/mpstat.log" & P2=$!
pidstat -t -p "$PID" -u -r -w -d 1 "$SECONDS_TO_CAPTURE" > "$OUT/pidstat.log" & P3=$!
trap 'kill "$P1" "$P2" "$P3" 2>/dev/null || true' EXIT
wait "$P1" "$P2" "$P3"
"$JAVA_HOME/bin/jcmd" "$PID" VM.native_memory summary.diff > "$OUT/nmt-end.txt"
free -b > "$OUT/memory-after.txt"
cat /proc/vmstat > "$OUT/vmstat-after.txt"
cat /proc/"$PID"/status > "$OUT/process-after.txt"
if [[ -r /sys/fs/cgroup/cpu.stat ]]; then cat /sys/fs/cgroup/cpu.stat > "$OUT/cgroup-after.txt"; fi
"$JAVA_HOME/bin/jfr" summary "$OUT/recording.jfr" > "$OUT/jfr-summary.txt"
# Preserve binary recordings; do not expand all allocation events into multi-GB JSON.
(cd "$OUT" && find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
printf 'Collected attribution evidence. Append interpretation and missing metrics to PERFORMANCE_VALIDATION.md.\n'
