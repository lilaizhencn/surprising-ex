#!/usr/bin/env bash
set -euo pipefail

# 定时保存交易快照，并清掉七天前的命令日志；只在已启动的主节点运行。
# 快照录制本身保留，恢复和回滚仍使用 recording.log 里的有效快照。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_ROOT="${RUNTIME_ROOT:-/var/lib/surprising/linear-perpetual/runtime}"
RUN_ID="${RUN_ID:-linear-perpetual-single-node}"
CLUSTER_DIR="$RUNTIME_ROOT/$RUN_ID/aeron/linear_perpetual/node0/cluster"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-27-openjdk-amd64}"
# ClusterTool is already included in the deployed Core executable. Application-only
# releases need not build a separate diagnostics module just to save a snapshot.
CORE_JAR="$ROOT_DIR/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"

if ! systemctl is-active --quiet surprising-linear-perpetual.service; then
  printf 'SNAPSHOT=SKIPPED reason=stack-not-active\n'
  exit 0
fi

if [[ ! -r "$CORE_JAR" || ! -s "$CORE_JAR" ]]; then
  printf 'SNAPSHOT=FAILED reason=core-jar-unavailable\n' >&2
  exit 1
fi

cluster_tool() {
  timeout 30 "$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
    -cp "$CORE_JAR" io.aeron.cluster.ClusterTool "$CLUSTER_DIR" "$@"
}

# 只删除命令日志里早于保留窗口的段。没有成交导出游标时不删，避免未导出的成交丢失。
retain_command_log() {
  local aeron_base aeron_dir checkpoint
  aeron_base="${CORE_AERON_BASE_DIR:-/dev/shm/surprising-aeron}"
  aeron_dir="${aeron_base}-surprising-linear_perpetual-0"
  checkpoint="${TRADE_EXPORT_CHECKPOINT:-$RUNTIME_ROOT/$RUN_ID/trade-export/checkpoint.bin}"
  timeout 120 "$JAVA_HOME/bin/java" --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
    -cp "$CORE_JAR" com.surprising.aeron.retention.ArchiveHistoryRetentionMain \
    --cluster-dir "$CLUSTER_DIR" \
    --aeron-dir "$aeron_dir" \
    --control-channel "aeron:ipc?term-length=1m" \
    --checkpoint "$checkpoint" \
    --retention-days 7
}

# Aeron returns 1 with no output for a healthy non-leader. JVM/classpath/mark-file
# failures can also return 1: their diagnostics must fail the systemd task.
if leader_output="$(cluster_tool is-leader 2>&1)"; then
  :
else
  leader_status=$?
  if (( leader_status == 1 )) && [[ -z "$leader_output" ]]; then
    printf 'SNAPSHOT=SKIPPED reason=node-not-leader\n'
    exit 0
  fi
  printf 'SNAPSHOT=FAILED reason=leader-check-failed exitCode=%s\n%s\n' \
    "$leader_status" "$leader_output" >&2
  exit 1
fi

snapshot_count() {
  cluster_tool recording-log | awk '{ n += gsub(/type=SNAPSHOT, isValid=true/, "&") } END { print n + 0 }'
}

if ! before="$(snapshot_count)"; then
  printf 'SNAPSHOT=FAILED reason=recording-log-unavailable\n' >&2
  exit 1
fi
if ! cluster_tool snapshot; then
  printf 'SNAPSHOT=FAILED reason=snapshot-request-failed\n' >&2
  exit 1
fi
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  if ! after="$(snapshot_count)"; then
    printf 'SNAPSHOT=FAILED reason=recording-log-unavailable\n' >&2
    exit 1
  fi
  if (( after >= before + 2 )); then
    printf 'SNAPSHOT=COMPLETE validRecordsBefore=%s validRecordsAfter=%s\n' "$before" "$after"
    retain_command_log
    exit 0
  fi
  sleep 2
done
printf 'SNAPSHOT=FAILED reason=completion-timeout\n' >&2
exit 1
