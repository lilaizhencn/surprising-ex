#!/usr/bin/env bash
# Persistent local U-margined perpetual environment; never deletes trading data.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_PERPETUAL_DIR:-$HOME/.local/share/surprising-ex/perpetual-pmm-20}"
CONFIG="${LOCAL_PERPETUAL_CONFIG:-$LOCAL_DIR/local.env}"
ACTION="${1:-up}"
fail() { echo "ERROR: $*" >&2; exit 1; }
case "$ACTION" in up|down|status|build) ;; *) fail "usage: $0 {up|down|status|build}" ;; esac
umask 077
mkdir -p "$LOCAL_DIR/logs" "$LOCAL_DIR/pids"
mkdir "$LOCAL_DIR/command.lock" 2>/dev/null || fail "another local-perpetual command is running ($LOCAL_DIR/command.lock)"
trap 'rmdir "$LOCAL_DIR/command.lock"' EXIT
if [[ ! -f "$CONFIG" ]]; then
  [[ "$ACTION" == up || "$ACTION" == build ]] || fail "configuration missing: $CONFIG"
  cat > "$CONFIG" <<CONFIG
# Local test environment only. Shell syntax; loaded once for the whole stack.
# Set MANAGE_POSTGRES=false to use an existing database with the credentials below.
MANAGE_POSTGRES=true
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=15432
POSTGRES_DB=surprising_exchange
POSTGRES_USER=surprising
POSTGRES_PASSWORD=$(openssl rand -hex 24)
# Set MANAGE_REDIS / MANAGE_KAFKA=false to use existing local dependencies.
MANAGE_REDIS=true
VALKEY_HOST=127.0.0.1
VALKEY_PORT=16379
MANAGE_KAFKA=true
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092
GATEWAY_JWT_SECRET=$(openssl rand -hex 48)
FRONTEND_PORT=5174
CONFIG
  echo "Created configuration: $CONFIG"
fi
set -a
source "$CONFIG"
set +a
export JAVA_HOME="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^    java.home = /{print $2; exit}')}"
export PATH="$JAVA_HOME/bin:$PATH"
# Homebrew keeps PostgreSQL keg-only. Other installations can supply PATH.
if ! command -v pg_ctl >/dev/null && command -v brew >/dev/null; then
  export PATH="$(brew --prefix postgresql@18)/bin:$PATH"
fi
export ARTIFACT_ROOT="$LOCAL_DIR/artifacts"
export RUNTIME_ROOT="$LOCAL_DIR/runtime" RUN_ID=local-perpetual POSTGRES_MODE=native
export CORE_AERON_BASE_DIR="$LOCAL_DIR/core-driver" APP_AERON_DIR="$LOCAL_DIR/app-driver"
export GATEWAY_PRODUCT_TRANSFER_ENABLED=false
export LOCAL_SIMULATED_TRADES_ENABLED="${LOCAL_SIMULATED_TRADES_ENABLED:-${MANAGE_POSTGRES:-false}}"
export PRICE_CONSUMER_REQUIRED_SYMBOLS="${PRICE_CONSUMER_REQUIRED_SYMBOLS:-$(paste -sd, "$ROOT/deployment/local-perpetual/symbols.txt")}"
export MM_BASE_QUANTITY_STEPS="${MM_BASE_QUANTITY_STEPS:-1000}"
export MM_ORDER_LEVELS="${MM_ORDER_LEVELS:-50}"
export MM_MAX_OPEN_ORDERS_PER_ACCOUNT_SYMBOL="${MM_MAX_OPEN_ORDERS_PER_ACCOUNT_SYMBOL:-100}"
FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/../surprising-ex-web}"
backend() { "$ROOT/scripts/linear-perpetual-single-node.sh" "$@"; }
label() { printf 'com.surprising.local-perpetual.%s' "$1"; }
alive() {
  local name="$1" pid
  [[ -f "$LOCAL_DIR/pids/$name.pid" ]] || return 1
  pid="$(cat "$LOCAL_DIR/pids/$name.pid")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  # launchd owns the current child; PID files are refreshed after KeepAlive restarts.
  if [[ "$(uname)" == Darwin ]]; then
    local details
    details="$(launchctl print "gui/$(id -u)/$(label "$name")" 2>/dev/null)" || return 1
    grep -Fq "$LOCAL_DIR" <<< "$details" || return 1
    pid="$(awk '/pid =/{print $3; exit}' <<< "$details")"
    [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null || return 1
    printf '%s\n' "$pid" > "$LOCAL_DIR/pids/$name.pid"
  else
    kill -0 "$pid" 2>/dev/null || return 1
    ps -p "$pid" -o command= | grep -F -- "$LOCAL_DIR" >/dev/null ||
      [[ "$(readlink "/proc/$pid/cwd")" == "$LOCAL_DIR/redis" ]]
  fi
}
start() {
  local name="$1" pid
  shift
  if alive "$name"; then return; fi
  if [[ "$(uname)" == Darwin ]]; then
    launchctl remove "$(label "$name")" >/dev/null 2>&1 || true
    launchctl submit -l "$(label "$name")" -o "$LOCAL_DIR/logs/$name.log" -e "$LOCAL_DIR/logs/$name.log" -- "$@"
    for ((i=0;i<10;i++)); do
      pid="$(launchctl print "gui/$(id -u)/$(label "$name")" 2>/dev/null | awk '/pid =/{print $3; exit}')" || true
      if [[ "$pid" =~ ^[0-9]+$ ]]; then break; fi
      sleep 1
    done
  else
    command -v setsid >/dev/null || fail 'setsid is required'
    nohup setsid "$@" > "$LOCAL_DIR/logs/$name.log" 2>&1 < /dev/null &
    pid=$!
  fi
  [[ "${pid:-}" =~ ^[0-9]+$ ]] || fail "could not start $name; see $LOCAL_DIR/logs/$name.log"
  echo "$pid" > "$LOCAL_DIR/pids/$name.pid"
}
stop() {
  local name="$1"
  if [[ "$(uname)" == Darwin ]]; then
    local details
    details="$(launchctl print "gui/$(id -u)/$(label "$name")" 2>/dev/null)" || details=''
    if [[ -n "$details" ]]; then
      grep -Fq "$LOCAL_DIR" <<< "$details" || fail "refusing to stop $name owned by another runtime"
      launchctl remove "$(label "$name")" >/dev/null 2>&1 || true
    fi
  elif alive "$name"; then
    kill -TERM "$(cat "$LOCAL_DIR/pids/$name.pid")"
  fi
  rm -f "$LOCAL_DIR/pids/$name.pid"
}
wait_port() {
  local host="$1" port="$2"
  for ((i=0;i<90;i++)); do
    if nc -z "$host" "$port" >/dev/null 2>&1; then return; fi
    sleep 1
  done
  fail "dependency unavailable $host:$port; see $LOCAL_DIR/logs"
}
free_port() { ! nc -z 127.0.0.1 "$1" >/dev/null 2>&1 || fail "port $1 already occupied by another process"; }
build() {
  java -version
  java -version 2>&1 | grep -Eq 'version "27([."]|$)' || fail 'HotSpot JDK 27 is required'
  java -version 2>&1 | grep -Eq 'OpenJDK.*Server VM|HotSpot' || fail 'HotSpot JDK 27 is required'
  mvn -version
  mvn -f "$ROOT/pom.xml" -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am package -DskipTests
}
case "$ACTION" in
  build) build; exit ;;
  status)
    backend status
    for port in 9094 9082 9095 9087 9096; do
      curl --fail --silent --max-time 5 "http://127.0.0.1:$port/actuator/health" >/dev/null || fail "unhealthy service on port $port"
    done
    # HTTP health alone does not prove the restarted Core has finished replay.
    curl --fail --silent --max-time 5 -H 'X-Product-Line: LINEAR_PERPETUAL' \
      'http://127.0.0.1:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT-SWAP&depth=1' \
      >/dev/null || fail 'Core query unavailable: process may still be recovering; inspect core-node0.log'
    for name in postgres redis kafka frontend checkpoints; do
      if alive "$name"; then echo "$name=RUNNING"; else echo "$name=STOPPED_OR_EXTERNAL"; fi
    done
    echo "PAGE=http://127.0.0.1:$FRONTEND_PORT/trade/usd-perpetual"
    exit ;;
  down)
    stop checkpoints
    stop frontend
    backend down
    stop kafka
    stop redis
    if [[ "${MANAGE_POSTGRES:-false}" == true && -f "$LOCAL_DIR/postgres/PG_VERSION" ]]; then
      pg_ctl -D "$LOCAL_DIR/postgres" -m fast -w stop || true
    fi
    stop postgres
    stop awake
    echo "Stopped. Data retained: $LOCAL_DIR"
    exit ;;
esac
java -version 2>&1 | grep -Eq 'version "27([."]|$)' || fail 'HotSpot JDK 27 is required'
java -version 2>&1 | grep -Eq 'OpenJDK.*Server VM|HotSpot' || fail 'HotSpot JDK 27 is required'
[[ $(df -Pk "$LOCAL_DIR" | awk 'END {print $4}') -gt 5242880 ]] || fail 'less than 5 GiB disk space'
for bin in curl nc psql node rsync python3; do command -v "$bin" >/dev/null || fail "missing executable: $bin"; done
if [[ "${MANAGE_POSTGRES:-false}" == true ]]; then
  for bin in initdb postgres pg_ctl createdb; do command -v "$bin" >/dev/null || fail "install PostgreSQL 18: missing $bin"; done
fi
if [[ "${MANAGE_REDIS:-false}" == true ]]; then command -v redis-server >/dev/null || fail 'install Redis: missing redis-server'; fi
if [[ "${MANAGE_KAFKA:-false}" == true ]]; then
  for bin in kafka-storage kafka-server-start; do command -v "$bin" >/dev/null || fail "install Kafka 4.3: missing $bin"; done
fi
[[ -f "$FRONTEND_DIR/node_modules/vite/bin/vite.js" ]] || fail "run npm ci in $FRONTEND_DIR first"
# A successful repeated up is a no-op; a partially running backend must be stopped explicitly.
if backend status > "$LOCAL_DIR/logs/status.log" 2>&1; then
  echo 'Backend already running.'
else
  for pidfile in "$RUNTIME_ROOT/$RUN_ID/pids/"*.pid; do
    [[ -f "$pidfile" ]] || continue
    if kill -0 "$(cat "$pidfile")" 2>/dev/null; then fail "partial backend detected; run $0 down before up"; fi
  done
  if [[ "${MANAGE_POSTGRES:-false}" == true ]] && ! alive postgres; then
    [[ "$POSTGRES_HOST" == 127.0.0.1 ]] || fail 'managed PostgreSQL must bind localhost'
    free_port "$POSTGRES_PORT"
    if [[ ! -f "$LOCAL_DIR/postgres/PG_VERSION" ]]; then
      printf '%s\n' "$POSTGRES_PASSWORD" > "$LOCAL_DIR/pg-password"
      initdb -D "$LOCAL_DIR/postgres" -U "$POSTGRES_USER" --pwfile="$LOCAL_DIR/pg-password" --auth-host=scram-sha-256 --auth-local=trust > "$LOCAL_DIR/logs/initdb.log"
      rm "$LOCAL_DIR/pg-password"
    fi
    start postgres /usr/bin/env LC_ALL=C "$(command -v postgres)" -D "$LOCAL_DIR/postgres" -h 127.0.0.1 -p "$POSTGRES_PORT"
    wait_port "$POSTGRES_HOST" "$POSTGRES_PORT"
    if ! PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d postgres -Atc 'SELECT datname FROM pg_database' | grep -Fxq "$POSTGRES_DB"; then
      PGPASSWORD="$POSTGRES_PASSWORD" createdb -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" "$POSTGRES_DB"
    fi
  fi
  if [[ "${MANAGE_POSTGRES:-false}" == true ]]; then
    schema_present="$(PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT to_regclass('public.instruments') IS NOT NULL")"
    if [[ ! -f "$LOCAL_DIR/catalog-ready" ]]; then
      [[ ! -d "$RUNTIME_ROOT/$RUN_ID/aeron" ]] || fail 'existing Core data without catalog marker; refusing to alter instrument units'
      if [[ "$schema_present" != t ]]; then
      PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$ROOT/init.sql" > "$LOCAL_DIR/logs/schema.log" 2>&1
      fi
      PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$ROOT/deployment/local-perpetual/catalog.sql" >> "$LOCAL_DIR/logs/schema.log" 2>&1
      touch "$LOCAL_DIR/catalog-ready"
    fi
  fi
  cp "$ROOT/deployment/local-perpetual/logback.xml" "$LOCAL_DIR/logback.xml"
  export LOGGING_CONFIG="file:$LOCAL_DIR/logback.xml"
  cp "$ROOT/deployment/local-perpetual/application-local.yml" "$LOCAL_DIR/application-local.yml"
  export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$LOCAL_DIR/application-local.yml"
  if [[ "${MANAGE_REDIS:-false}" == true ]] && ! alive redis; then
    [[ "$VALKEY_HOST" == 127.0.0.1 ]] || fail 'managed Redis must bind localhost'
    free_port "$VALKEY_PORT"
    mkdir -p "$LOCAL_DIR/redis"
    start redis "$(command -v redis-server)" --bind 127.0.0.1 --port "$VALKEY_PORT" --dir "$LOCAL_DIR/redis" --appendonly yes --logfile "$LOCAL_DIR/logs/redis-server.log"
  fi
  if [[ "${MANAGE_KAFKA:-false}" == true ]] && ! alive kafka; then
    [[ "$KAFKA_BOOTSTRAP_SERVERS" == 127.0.0.1:19092 ]] || fail 'managed Kafka uses 127.0.0.1:19092; disable management for external Kafka'
    free_port 19092
    free_port 19093
    cat > "$LOCAL_DIR/kafka.properties" <<KAFKA
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:19093
listeners=PLAINTEXT://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093
advertised.listeners=PLAINTEXT://127.0.0.1:19092
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
inter.broker.listener.name=PLAINTEXT
log.dirs=$LOCAL_DIR/kafka
num.partitions=32
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
log.retention.hours=24
log.segment.bytes=67108864
log.retention.bytes=134217728
KAFKA
    if [[ ! -f "$LOCAL_DIR/kafka/meta.properties" ]]; then
      kafka-storage format --standalone -t "$(kafka-storage random-uuid)" -c "$LOCAL_DIR/kafka.properties" > "$LOCAL_DIR/logs/kafka-format.log" 2>&1
    fi
    start kafka /usr/bin/env JAVA_HOME="$JAVA_HOME" KAFKA_HEAP_OPTS='-Xms256m -Xmx512m' LOG_DIR="$LOCAL_DIR/logs" "$(command -v kafka-server-start)" "$LOCAL_DIR/kafka.properties"
  fi
  wait_port "$POSTGRES_HOST" "$POSTGRES_PORT"
  wait_port "$VALKEY_HOST" "$VALKEY_PORT"
  wait_port "${KAFKA_BOOTSTRAP_SERVERS%:*}" "${KAFKA_BOOTSTRAP_SERVERS##*:}"
  if [[ ! -f "$ROOT/surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar" ]]; then build; fi
  if [[ "$(uname)" == Darwin ]]; then start awake /bin/bash -c '/usr/bin/caffeinate -is & wait' "$LOCAL_DIR"; fi
  # launchd cannot reliably read a checkout under macOS Desktop privacy protection.
  # Stage exactly the built artifacts, keeping the same module-relative paths.
  export ARTIFACT_ROOT="$LOCAL_DIR/artifacts"
  while IFS= read -r jar; do
    relative="${jar#"$ROOT/"}"
    mkdir -p "$ARTIFACT_ROOT/$(dirname "$relative")"
    cp "$jar" "$ARTIFACT_ROOT/$relative"
  done < <(find "$ROOT" -path '*/target/*.jar' -not -path '*/target/*/*' -type f)
  backend up
fi
# Test funds are limited to this launcher-owned database and use idempotent references.
if [[ "${MANAGE_POSTGRES:-false}" == true && ! -f "$LOCAL_DIR/maker-funded" ]]; then
  for user_id in {900101..900120} 910001; do
    curl --fail --silent --show-error --max-time 30 -H 'Content-Type: application/json' \
      -d "{\"userId\":$user_id,\"asset\":\"USDT\",\"amountUnits\":10000000000000,\"referenceId\":\"local-maker-initial-$user_id\",\"reason\":\"LOCAL DEMO test funds\"}" \
      http://127.0.0.1:9094/api/v1/accounts/admin/balance-adjustments > "$LOCAL_DIR/maker-$user_id-initial.json"
  done
  touch "$LOCAL_DIR/maker-funded"
fi
# One-time simulated taker fee budget; never replenish based on trading losses.
if [[ "${MANAGE_POSTGRES:-false}" == true && "$LOCAL_SIMULATED_TRADES_ENABLED" == true && ! -f "$LOCAL_DIR/taker-fee-budget-funded" ]]; then
  curl --fail --silent --show-error --max-time 30 -H 'Content-Type: application/json' \
    -d '{"userId":910001,"asset":"USDT","amountUnits":90000000000000,"referenceId":"local-taker-fee-budget-910001","reason":"LOCAL DEMO simulated taker fee budget"}' \
    http://127.0.0.1:9094/api/v1/accounts/admin/balance-adjustments > "$LOCAL_DIR/taker-fee-budget.json"
  touch "$LOCAL_DIR/taker-fee-budget-funded"
fi
# Fixed extra capital for a longer local demonstration; never replenish losses automatically.
if [[ "${MANAGE_POSTGRES:-false}" == true && "$LOCAL_SIMULATED_TRADES_ENABLED" == true && ! -f "$LOCAL_DIR/extended-demo-budget-funded" ]]; then
  for user_id in {900101..900120} 910001; do
    amount_units=90000000000000
    [[ "$user_id" == 910001 ]] && amount_units=900000000000000
    curl --fail --silent --show-error --max-time 30 -H 'Content-Type: application/json' \
      -d "{\"userId\":$user_id,\"asset\":\"USDT\",\"amountUnits\":$amount_units,\"referenceId\":\"local-extended-demo-budget-$user_id\",\"reason\":\"LOCAL DEMO extended quote and taker fee budget\"}" \
      http://127.0.0.1:9094/api/v1/accounts/admin/balance-adjustments > "$LOCAL_DIR/extended-demo-budget-$user_id.json"
  done
  touch "$LOCAL_DIR/extended-demo-budget-funded"
fi
# Completed snapshots bound recovery work after an unattended service restart.
mkdir -p "$LOCAL_DIR/bin"
cp "$ROOT/scripts/checkpoint-local-perpetual.py" "$LOCAL_DIR/bin/checkpoint-local-perpetual.py"
start checkpoints "$(command -v python3)" "$LOCAL_DIR/bin/checkpoint-local-perpetual.py" \
  --runtime "$LOCAL_DIR" --java "$JAVA_HOME/bin/java" \
  --tools-jar "$ARTIFACT_ROOT/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
if ! alive frontend; then
  free_port "$FRONTEND_PORT"
  mkdir -p "$LOCAL_DIR/web"
  rsync -a --delete --exclude .git --exclude .codegraph --exclude .wrangler --exclude /dist "$FRONTEND_DIR/" "$LOCAL_DIR/web/"
  # Live feeds must not pay React development instrumentation costs in the demo.
  (cd "$LOCAL_DIR/web" && npm run build) > "$LOCAL_DIR/logs/frontend-build.log" 2>&1
  start frontend /bin/bash -c 'cd "$1"; exec "$2" "$1/node_modules/vite/bin/vite.js" preview --host 127.0.0.1 --port "$3" --strictPort' "$LOCAL_DIR" "$LOCAL_DIR/web" "$(command -v node)" "$FRONTEND_PORT"
fi
wait_port 127.0.0.1 "$FRONTEND_PORT"
ready_deadline=$((SECONDS + 180))
until python3 "$ROOT/scripts/check-local-perpetual.py" > "$LOCAL_DIR/market-readiness.json"; do
  (( SECONDS < ready_deadline )) || fail "20-symbol market not ready; see $LOCAL_DIR/market-readiness.json (services retained for diagnosis)"
  sleep 5
done
echo 'MARKETS=20/20 ready (three index sources, mark price, two-sided books)'
echo "PAGE=http://127.0.0.1:$FRONTEND_PORT/trade/usd-perpetual"
echo "CONFIG=$CONFIG"
echo "LOGS=$RUNTIME_ROOT/$RUN_ID/logs"
