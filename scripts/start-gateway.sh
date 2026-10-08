#!/usr/bin/env bash
set -euo pipefail

# 单一公共 Gateway；产品线 Core、数据库、Kafka、Redis 必须事先可用。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${GATEWAY_PRODUCT_LINES:?set GATEWAY_PRODUCT_LINES, for example LINEAR_PERPETUAL,SPOT}"
JAVA_BIN="${JAVA_HOME:?HotSpot JDK 27 JAVA_HOME is required}/bin/java"
version="$("$JAVA_BIN" -version 2>&1)"
if [[ ! "$version" =~ version[[:space:]]\"27[.\"] ]] || [[ "$version" == *OpenJ9* ]] \
  || ! [[ "$version" =~ HotSpot|OpenJDK.*Server\ VM ]]; then
  printf '%s\n' 'HotSpot JDK 27 is required' >&2
  exit 2
fi
GATEWAY_JAR="${GATEWAY_JAR:-$ROOT_DIR/surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar}"
[[ -f "$GATEWAY_JAR" ]] || { printf 'Gateway jar missing: %s\n' "$GATEWAY_JAR" >&2; exit 2; }
exec "$JAVA_BIN" "-Xms${JVM_GATEWAY_XMS:-256m}" "-Xmx${JVM_GATEWAY_XMX:-1g}" \
  --enable-native-access=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -jar "$GATEWAY_JAR" "$@"
