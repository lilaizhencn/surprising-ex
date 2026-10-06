#!/usr/bin/env bash
set -euo pipefail

# 定时保存交易快照，保留所有原始日志；只在已启动的主节点运行。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_ROOT="${RUNTIME_ROOT:-/var/lib/surprising/linear-perpetual/runtime}"
RUN_ID="${RUN_ID:-linear-perpetual-single-node}"
CLUSTER_DIR="$RUNTIME_ROOT/$RUN_ID/aeron/linear_perpetual/node0/cluster"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-27-openjdk-amd64}"
TOOL_JAR="$ROOT_DIR/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"

if ! systemctl is-active --quiet surprising-linear-perpetual.service; then
  printf 'SNAPSHOT=SKIPPED reason=stack-not-active\n'
  exit 0
fi

cluster_tool() {
  timeout 30 "$JAVA_HOME/bin/java" --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "$TOOL_JAR" io.aeron.cluster.ClusterTool "$CLUSTER_DIR" "$@"
}

if ! cluster_tool is-leader; then
  printf 'SNAPSHOT=SKIPPED reason=node-not-leader\n'
  exit 0
fi

snapshot_count() {
  cluster_tool recording-log | awk '{ n += gsub(/type=SNAPSHOT, isValid=true/, "&") } END { print n + 0 }'
}

before="$(snapshot_count)"
cluster_tool snapshot
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  after="$(snapshot_count)"
  if (( after >= before + 2 )); then
    printf 'SNAPSHOT=COMPLETE validRecordsBefore=%s validRecordsAfter=%s\n' "$before" "$after"
    exit 0
  fi
  sleep 2
done
printf 'SNAPSHOT=FAILED reason=completion-timeout\n' >&2
exit 1
