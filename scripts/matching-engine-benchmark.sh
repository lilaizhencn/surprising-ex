#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORDERS="${1:-${BENCHMARK_ORDERS:-100000}}"
WARMUP_ORDERS="${2:-${BENCHMARK_WARMUP_ORDERS:-10000}}"
CP_FILE="$(mktemp /tmp/surprising-matching-benchmark-cp.XXXXXX)"

cleanup() {
  rm -f "${CP_FILE}"
}
trap cleanup EXIT

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-trading-api -am -DskipTests install

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-matching-provider -am -DskipTests test-compile

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -U -pl :surprising-matching-provider -DincludeScope=test \
  -Dmdep.outputFile="${CP_FILE}" dependency:build-classpath

CLASSPATH="${ROOT_DIR}/surprising-trading/surprising-matching-provider/target/test-classes"
CLASSPATH="${CLASSPATH}:${ROOT_DIR}/surprising-trading/surprising-matching-provider/target/classes"
CLASSPATH="${CLASSPATH}:${ROOT_DIR}/surprising-trading/surprising-trading-api/target/classes"
CLASSPATH="${CLASSPATH}:$(cat "${CP_FILE}")"

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
  -cp "${CLASSPATH}" \
  com.surprising.trading.matching.benchmark.ExchangeCoreEngineBenchmark \
  "${ORDERS}" "${WARMUP_ORDERS}"
