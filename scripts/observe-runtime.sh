#!/usr/bin/env bash
# Sample the running local trading JVMs.
# JFR is dumped with jcmd and summarized with the JDK jfr tool (the same events JMC shows).
# async-profiler runs only when asprof is on PATH. JITWatch needs JIT_LOG_ENABLED=true at the next start.
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/27.0.1.0.3/libexec/openjdk.jdk/Contents/Home}"
JCMD="$JAVA_HOME/bin/jcmd"
JFR="$JAVA_HOME/bin/jfr"
OUT="${1:-${TMPDIR:-/tmp}/surprising-observe}"
mkdir -p "$OUT"
pids=()
while IFS= read -r pid; do
  [[ -n "$pid" ]] && pids+=("$pid")
done < <("$JCMD" -l | awk '/surprising.launcher.identity/ {print $1}')
if [[ ${#pids[@]} -eq 0 ]]; then
  echo "no trading JVM with surprising.launcher.identity is running" >&2
  exit 1
fi
for pid in "${pids[@]}"; do
  identity="$("$JCMD" "$pid" VM.system_properties | awk -F= '/surprising.launcher.identity/ {print $2; exit}')"
  safe="${identity//\//-}"
  file="$OUT/$safe.jfr"
  echo "dump $identity -> $file"
  "$JCMD" "$pid" JFR.dump "name=surprising" "filename=$file" >/dev/null
  echo "---- $identity hot-methods ----"
  "$JFR" view hot-methods "$file" | head -n 18
  echo "---- $identity gc-pauses ----"
  "$JFR" view gc-pauses "$file" | head -n 16
  echo "---- $identity deoptimizations ----"
  "$JFR" view deoptimizations-by-reason "$file" | head -n 12
  if command -v asprof >/dev/null 2>&1; then
    echo "---- $identity async-profiler wall 10s ----"
    asprof -d 10 -e wall -f "$OUT/$safe-wall.html" "$pid"
  fi
done
if ! command -v asprof >/dev/null 2>&1; then
  echo "asprof is not on PATH; CPU and wall flame graphs were not collected"
fi
echo "JIT compilation logs are written only when a process is started with JIT_LOG_ENABLED=true"
echo "samples are in $OUT"
