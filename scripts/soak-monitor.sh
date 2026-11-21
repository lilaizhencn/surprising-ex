#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${DURATION_SECONDS:-3600}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-30}"
RUNTIME_DIR="${RUNTIME_DIR:-$HOME/.local/share/surprising-ex/perpetual-pmm-20/runtime/local-perpetual}"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:9094}"
MAKER_URL="${MAKER_URL:-http://127.0.0.1:9096}"
SYMBOLS="${SYMBOLS:-604,653,866,1001,1002,1003}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration) DURATION_SECONDS="$2"; shift 2 ;;
    --interval) INTERVAL_SECONDS="$2"; shift 2 ;;
    --runtime) RUNTIME_DIR="$2"; shift 2 ;;
    --gateway) GATEWAY_URL="$2"; shift 2 ;;
    --maker) MAKER_URL="$2"; shift 2 ;;
    --symbols) SYMBOLS="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"
JFR_DIR="$RUNTIME_DIR/jfr"
mkdir -p "$LOG_DIR" "$JFR_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$LOG_DIR/soak-report-$STAMP.md"

echo "# Surprising-Ex Soak Monitor ($STAMP)" > "$REPORT"
echo "- Duration: ${DURATION_SECONDS}s, Interval: ${INTERVAL_SECONDS}s" >> "$REPORT"
echo "- Runtime: \`$RUNTIME_DIR\`" >> "$REPORT"
echo "" >> "$REPORT"
echo "| Time (UTC) | Core RSS/Thr | Gateway RSS/Thr | Maker RSS/Thr | Books (bids/asks) | Nacos-9848 | SnapshotErr | 503 | BalReject |" >> "$REPORT"
echo "| --- | --- | --- | --- | --- | --- | --- | --- | --- |" >> "$REPORT"

proc_stat() {
  local name="$1" pid_file="$PID_DIR/$1.pid" pid rss thr
  if [[ ! -f "$pid_file" ]]; then echo "down"; return; fi
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if [[ ! "$pid" =~ ^[0-9]+$ ]] || ! kill -0 "$pid" 2>/dev/null; then echo "dead"; return; fi
  rss="$(ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%.0fM", $1/1024}' || echo "?")"
  thr="$(jcmd "$pid" Thread.print 2>/dev/null | grep -c 'nid=' || echo "?")"
  echo "${rss}/${thr}t"
}

count_pattern() {
  local pat="$1"
  { grep -hE "$pat" "$LOG_DIR"/*.log "$LOG_DIR"/*/*.log 2>/dev/null || true; } | wc -l | tr -d ' '
}

BASE_NACOS="$(count_pattern '127\.0\.0\.1:9848|Connection refused.*9848')"
BASE_SNAP="$(count_pattern 'Matcher settlement ring is unfinished|SnapshotFenceTimeoutException')"
BASE_503="$(count_pattern '503 read-view unavailable|503 maker quote-inputs|status 503')"
BASE_BAL="$(count_pattern 'INSUFFICIENT_AVAILABLE_BALANCE')"

END_EPOCH=$(( $(date +%s) + DURATION_SECONDS ))
SAMPLE=0

while (( $(date +%s) < END_EPOCH )); do
  SAMPLE=$((SAMPLE + 1))
  NOW_UTC="$(date -u +%H:%M:%S)"
  CORE_STAT="$(proc_stat core-node0)"
  GW_STAT="$(proc_stat gateway)"
  MK_STAT="$(proc_stat maker)"

  BOOK_SUMMARY=""
  IFS=',' read -ra SYM_ARR <<< "$SYMBOLS"
  for sym in "${SYM_ARR[@]}"; do
    PL="LINEAR_PERPETUAL"
    if [[ "$sym" == 100* ]]; then PL="SPOT"; fi
    DEPTH="$(curl -fsS --max-time 2 "$GATEWAY_URL/api/v1/trading/market/orderbook?productLine=$PL&instrumentId=$sym&depth=5" 2>/dev/null | \
      python3 -c 'import sys,json; d=json.load(sys.stdin); print(str(len(d.get("bids",[])))+"/"+str(len(d.get("asks",[]))))' 2>/dev/null || echo "err")"
    BOOK_SUMMARY="${BOOK_SUMMARY}${sym}:${DEPTH} "
  done

  NACOS_CNT="$(( $(count_pattern '127\.0\.0\.1:9848|Connection refused.*9848') - BASE_NACOS ))"
  SNAP_CNT="$(( $(count_pattern 'Matcher settlement ring is unfinished|SnapshotFenceTimeoutException') - BASE_SNAP ))"
  ERR503_CNT="$(( $(count_pattern '503 read-view unavailable|503 maker quote-inputs|status 503') - BASE_503 ))"
  BAL_CNT="$(( $(count_pattern 'INSUFFICIENT_AVAILABLE_BALANCE') - BASE_BAL ))"

  echo "| $NOW_UTC | $CORE_STAT | $GW_STAT | $MK_STAT | ${BOOK_SUMMARY% } | $NACOS_CNT | $SNAP_CNT | $ERR503_CNT | $BAL_CNT |" >> "$REPORT"
  echo "[$NOW_UTC] sample=$SAMPLE core=$CORE_STAT gw=$GW_STAT mk=$MK_STAT books=[${BOOK_SUMMARY% }] nacos=$NACOS_CNT snap=$SNAP_CNT 503=$ERR503_CNT bal=$BAL_CNT"

  # Every 10 samples, capture GC heap summary & JFR dump if jcmd is available
  if (( SAMPLE % 10 == 0 )); then
    for svc in core-node0 gateway maker; do
      if [[ -f "$PID_DIR/$svc.pid" ]]; then
        pid="$(cat "$PID_DIR/$svc.pid" 2>/dev/null || true)"
        if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
          jcmd "$pid" GC.heap_info >> "$LOG_DIR/soak-heap-$STAMP.log" 2>&1 || true
          jcmd "$pid" JFR.dump name=surprising filename="$JFR_DIR/$svc-soak-$STAMP.jfr" >/dev/null 2>&1 || true
        fi
      fi
    done
  fi

  sleep "$INTERVAL_SECONDS"
done

echo "" >> "$REPORT"
echo "Completed $SAMPLE samples at $(date -u +%Y-%m-%dT%H:%M:%SZ)." >> "$REPORT"
echo "SOAK_REPORT=$REPORT"
