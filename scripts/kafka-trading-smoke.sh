#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
case "$PRODUCT_LINE" in
  SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
  *) printf 'unsupported PRODUCT_LINE=%s\n' "$PRODUCT_LINE" >&2; exit 2 ;;
esac
if [[ -x "/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | rg -q 'version "25|openjdk version "25'; then
  export PATH="$JAVA_HOME/bin:$PATH"
else
  printf 'JDK 25 not found; set JAVA_HOME to a JDK 25 installation\n' >&2
  exit 2
fi

mvn -q -pl surprising-aeron-core/surprising-aeron-exporter -am test
printf 'kafkaTradingSmoke=PASS productLine=%s scope=CORE_INPUT_EXPORT_BRIDGE\n' "$PRODUCT_LINE"
