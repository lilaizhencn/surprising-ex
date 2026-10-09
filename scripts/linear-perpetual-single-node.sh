#!/usr/bin/env bash
set -euo pipefail

# Server/test entrypoint. The generic launcher still owns the process lifecycle;
# this file only fixes the product line and the single-node deployment boundary.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 单分区测试部署统一采用配套消费者并发，避免依赖旧环境遗留的 2/8 线程配置。
set -a
source "$ROOT_DIR/deployment/test-single-node/kafka-single-partition.env"
set +a

case "${1:-}" in
  start|up) ACTION=up; shift ;;
  stop|down) ACTION=down; shift ;;
  status) ACTION=status; shift ;;
  dry-run) ACTION=dry-run; shift ;;
  fresh) ACTION=fresh; shift ;;
  test) ACTION=test; shift ;;
esac

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/lib/jvm/java-27-openjdk-amd64/bin/java ]]; then
    JAVA_HOME=/usr/lib/jvm/java-27-openjdk-amd64
  elif command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 \
      | awk -F'= ' '/^    java.home = /{print $2; exit}')"
  fi
fi

export JAVA_HOME
export ACTION="${ACTION:-up}"
export PRODUCT_LINE=LINEAR_PERPETUAL
export RUN_ID="${RUN_ID:-linear-perpetual-single-node}"
export RUNTIME_ROOT="${RUNTIME_ROOT:-/var/lib/surprising/linear-perpetual/runtime}"
export POSTGRES_MODE="${POSTGRES_MODE:-native}"
export POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export POSTGRES_DB="${POSTGRES_DB:-surprising_exchange}"
export POSTGRES_USER="${POSTGRES_USER:-surprising}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-surprising}"
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
export VALKEY_HOST="${VALKEY_HOST:-127.0.0.1}"
export VALKEY_PORT="${VALKEY_PORT:-6379}"
export AERON_CLUSTER_HOSTNAMES="${AERON_CLUSTER_HOSTNAMES:-127.0.0.1}"
export AERON_EGRESS_HOSTNAME="${AERON_EGRESS_HOSTNAME:-127.0.0.1}"
export CORE_AERON_BASE_DIR="${CORE_AERON_BASE_DIR:-/dev/shm/surprising-aeron}"
export APP_AERON_DIR="${APP_AERON_DIR:-/dev/shm/surprising-app-linear-perpetual}"
export REALTIME_ENABLED="${REALTIME_ENABLED:-true}"
export TRADE_EXPORT_ENABLED="${TRADE_EXPORT_ENABLED:-true}"
export REALTIME_ROUTER_PORT="${REALTIME_ROUTER_PORT:-9095}"
export PRICE_INDEX_REQUIRED_INSTRUMENT_IDS="${PRICE_INDEX_REQUIRED_INSTRUMENT_IDS:-}"
export PRICE_CONSUMER_REQUIRED_INSTRUMENT_IDS="${PRICE_CONSUMER_REQUIRED_INSTRUMENT_IDS:-}"
export JVM_XMS="${JVM_XMS:-512m}"
export JVM_XMX="${JVM_XMX:-1g}"
# 持续录制使用低开销 default；临时深入采样再通过 jcmd 开启限时 profile。
export CORE_LATENCY_DIAGNOSTICS="${CORE_LATENCY_DIAGNOSTICS:-true}"
export JFR_ENABLED="${JFR_ENABLED:-true}"
export JFR_SETTINGS="${JFR_SETTINGS:-default}"
export JFR_MAX_SIZE="${JFR_MAX_SIZE:-64m}"
export JFR_MAX_AGE="${JFR_MAX_AGE:-30m}"
export LOGGING_CONFIG="${LOGGING_CONFIG:-file:$ROOT_DIR/deployment/test-single-node/logback.xml}"
export SERVICE_HEALTH_TIMEOUT_SECONDS="${SERVICE_HEALTH_TIMEOUT_SECONDS:-300}"

exec "$ROOT_DIR/scripts/start-product-line-providers.sh" "$@"
