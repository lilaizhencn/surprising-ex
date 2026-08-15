#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORDERS="${1:-${BENCHMARK_ORDERS:-100000}}"
WARMUP_ORDERS="${2:-${BENCHMARK_WARMUP_ORDERS:-10000}}"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
if [[ -z "${JAVA_HOME}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "JAVA_HOME must point to JDK 25" >&2
  exit 1
fi

JAVA_HOME="${JAVA_HOME}" mvn -q -pl :surprising-aeron-tools -am -DskipTests package

TOOLS_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
if [[ ! -f "${TOOLS_JAR}" ]]; then
  echo "missing Aeron tools jar: ${TOOLS_JAR}" >&2
  exit 1
fi

exec "${JAVA_HOME}/bin/java" \
  --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "${TOOLS_JAR}" \
  com.surprising.aeron.service.CoreInMemoryBenchmark \
  "${ORDERS}" "${WARMUP_ORDERS}"
