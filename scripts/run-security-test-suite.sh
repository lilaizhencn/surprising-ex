#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
SECURITY_EXECUTE="${SECURITY_EXECUTE:-false}"
SECURITY_AUTHORIZED="${SECURITY_AUTHORIZED:-false}"
SECURITY_TARGET="${SECURITY_TARGET:-}"
SECURITY_TARGET_ALLOWLIST="${SECURITY_TARGET_ALLOWLIST:-}"
SECURITY_SCENARIOS="${SECURITY_SCENARIOS:-passive authz websocket kafka concurrency}"
SECURITY_EVIDENCE_DIR="${SECURITY_EVIDENCE_DIR:-/tmp/surprising-security-tests/$(date -u +%Y%m%dT%H%M%SZ)/${PRODUCT_LINE}}"
SECURITY_RATE_LIMIT="${SECURITY_RATE_LIMIT:-2}"
SECURITY_AUTHZ_CASE_FILE="${SECURITY_AUTHZ_CASE_FILE:-}"
SECURITY_WS_URL="${SECURITY_WS_URL:-}"
SECURITY_WS_USER_ID="${SECURITY_WS_USER_ID:-}"
SECURITY_WS_OTHER_USER_ID="${SECURITY_WS_OTHER_USER_ID:-}"
SECURITY_RUN_CONCURRENCY="${SECURITY_RUN_CONCURRENCY:-false}"
SECURITY_RUN_FUNDS_RECONCILE="${SECURITY_RUN_FUNDS_RECONCILE:-false}"
SECURITY_RUN_NMAP="${SECURITY_RUN_NMAP:-false}"
SECURITY_RUN_NUCLEI="${SECURITY_RUN_NUCLEI:-false}"
SECURITY_RUN_FUZZ="${SECURITY_RUN_FUZZ:-false}"
SECURITY_FUZZ_WORDLIST="${SECURITY_FUZZ_WORDLIST:-}"
SECURITY_FUZZ_RESULT_FILE="${SECURITY_FUZZ_RESULT_FILE:-}"
SECURITY_PORT_RESULT_FILE="${SECURITY_PORT_RESULT_FILE:-}"
SECURITY_KAFKA_BOOTSTRAP_SERVERS="${SECURITY_KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
SECURITY_KAFKA_RESULT_FILE="${SECURITY_KAFKA_RESULT_FILE:-}"
SECURITY_CONCURRENCY_RESULT_FILE="${SECURITY_CONCURRENCY_RESULT_FILE:-}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect

fail() { echo "SECURITY_SUITE_FAIL: $*" >&2; exit 1; }

case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac
case "${SECURITY_EXECUTE}" in true|false) ;; *) fail "SECURITY_EXECUTE must be true or false" ;; esac
case "${SECURITY_AUTHORIZED}" in true|false) ;; *) fail "SECURITY_AUTHORIZED must be true or false" ;; esac
case "${SECURITY_RUN_CONCURRENCY}" in true|false) ;; *) fail "SECURITY_RUN_CONCURRENCY must be true or false" ;; esac
case "${SECURITY_RUN_FUNDS_RECONCILE}" in true|false) ;; *) fail "SECURITY_RUN_FUNDS_RECONCILE must be true or false" ;; esac
case "${SECURITY_RUN_NMAP}" in true|false) ;; *) fail "SECURITY_RUN_NMAP must be true or false" ;; esac
case "${SECURITY_RUN_NUCLEI}" in true|false) ;; *) fail "SECURITY_RUN_NUCLEI must be true or false" ;; esac
case "${SECURITY_RUN_FUZZ}" in true|false) ;; *) fail "SECURITY_RUN_FUZZ must be true or false" ;; esac
[[ "${SECURITY_RATE_LIMIT}" =~ ^[1-9][0-9]*$ ]] || fail "SECURITY_RATE_LIMIT must be positive"

origin_of() {
  printf '%s' "$1" | sed -E 's#^(https?://[^/]+).*$#\1#'
}

validate_target() {
  [[ -n "${SECURITY_TARGET}" && -n "${SECURITY_TARGET_ALLOWLIST}" ]] || fail "SECURITY_TARGET and SECURITY_TARGET_ALLOWLIST are required"
  [[ "${SECURITY_TARGET}" =~ ^https?://[^[:space:]]+$ ]] || fail "SECURITY_TARGET must be an http(s) URL"
  local forbidden target_origin candidate matched=false
  for forbidden in '*' '?' '$' '`' ';' '|' '&' '(' ')' '\\' '<' '>' "'" '"'; do
    [[ "${SECURITY_TARGET}" != *"${forbidden}"* ]] || fail "SECURITY_TARGET contains forbidden characters"
  done
  target_origin="$(origin_of "${SECURITY_TARGET}")"
  for candidate in ${SECURITY_TARGET_ALLOWLIST}; do
    [[ "${candidate}" == "${target_origin}" ]] && matched=true
  done
  [[ "${matched}" == "true" ]] || fail "SECURITY_TARGET origin is not in SECURITY_TARGET_ALLOWLIST"
}

validate_scenarios() {
  local scenario
  for scenario in ${SECURITY_SCENARIOS}; do
    case "${scenario}" in passive|authz|websocket|kafka|http-fuzz|resource|concurrency|dependencies|canary) ;;
      *) fail "unsupported SECURITY_SCENARIO=${scenario}" ;;
    esac
  done
  if [[ "${TEST_PROFILE}" == "cloud-production" && "${SECURITY_EXECUTE}" == "true" ]]; then
    for scenario in ${SECURITY_SCENARIOS}; do
      case "${scenario}" in passive|canary) ;;
        *) fail "cloud-production active security only permits passive/canary" ;;
      esac
    done
  fi
}

mkdir -p "${SECURITY_EVIDENCE_DIR}"
validate_scenarios
test_profile_write_manifest "${SECURITY_EVIDENCE_DIR}/environment-manifest.env"
{
  echo "product_line=${PRODUCT_LINE}"
  echo "profile=${TEST_PROFILE}"
  echo "security_execute=${SECURITY_EXECUTE}"
  echo "security_authorized=${SECURITY_AUTHORIZED}"
  echo "target_origin=$(origin_of "${SECURITY_TARGET:-not-set}")"
  echo "scenarios=${SECURITY_SCENARIOS}"
  echo "rate_limit=${SECURITY_RATE_LIMIT}"
  echo "run_concurrency=${SECURITY_RUN_CONCURRENCY}"
  echo "run_funds_reconcile=${SECURITY_RUN_FUNDS_RECONCILE}"
  echo "fuzz_result_file=${SECURITY_FUZZ_RESULT_FILE}"
  echo "port_result_file=${SECURITY_PORT_RESULT_FILE}"
  echo "kafka_result_file=${SECURITY_KAFKA_RESULT_FILE}"
  echo "concurrency_result_file=${SECURITY_CONCURRENCY_RESULT_FILE}"
} >"${SECURITY_EVIDENCE_DIR}/manifest.env"
printf '%s\n' ${SECURITY_TARGET_ALLOWLIST:-not-set} >"${SECURITY_EVIDENCE_DIR}/target-allowlist.txt"
{
  echo "scenario\tmode\tcommand or fixture\tPASS condition"
  echo "passive\tpassive\tcurl -D passive.headers -o passive.body TARGET\tno sensitive endpoint exposure or secret disclosure"
  echo "authz\tnegative\tmissing token, user mismatch, admin path and product-line mismatch\t401/403/400 and no business fact"
  echo "websocket\tnegative\tproduct-line-websocket-smoke.mjs\tno anonymous private access or cross-user event"
  echo "kafka\tpassive\tkafka ACL/list/group/topic key checks\tunauthorized read/write rejected and key isolation holds"
  echo "http-fuzz\tactive\tlimited ffuf/Nuclei baseline\t4xx/413/429, bounded resources, no fact created"
  echo "resource\tactive\tapproved low-rate input/connection limits\tno OOM/FullGC/unbounded queue and recovery to baseline"
  echo "concurrency\tactive\tsecurity-concurrency-race.sh\tno duplicate fact/freeze/settlement and funds reconcile zero"
  echo "dependencies\tpassive\tdependency/secret/config scanner\tno unaccepted high/critical issue or secret"
} >"${SECURITY_EVIDENCE_DIR}/security-plan.tsv"

if [[ "${SECURITY_EXECUTE}" == "false" ]]; then
  echo "SECURITY DRY_RUN PLAN_ONLY evidence=${SECURITY_EVIDENCE_DIR} profile=${TEST_PROFILE} product_line=${PRODUCT_LINE}"
  exit 0
fi

[[ "${SECURITY_AUTHORIZED}" == "true" ]] || fail "SECURITY_AUTHORIZED=true is required for active security testing"
validate_target

FAILED=false
SUMMARY_FILE="${SECURITY_EVIDENCE_DIR}/summary.md"
printf '# Security test summary\n\n| scenario | result | evidence |\n|---|---|---|\n' >"${SUMMARY_FILE}"

record_result() {
  local scenario="$1" result="$2" detail="$3"
  echo "| ${scenario} | ${result} | ${detail} |" >>"${SUMMARY_FILE}"
  if [[ "${result}" == "FAIL" || "${result}" == "INCONCLUSIVE" || "${result}" == "SKIPPED_TOOL" ]]; then
    FAILED=true
  fi
}

run_passive() {
  local base path code
  base="${SECURITY_TARGET%/}"
  curl --connect-timeout 5 --max-time 30 -sS -D "${SECURITY_EVIDENCE_DIR}/passive.headers" -o "${SECURITY_EVIDENCE_DIR}/passive.body" "${SECURITY_TARGET}" >"${SECURITY_EVIDENCE_DIR}/passive.curl.log" 2>&1 || {
    record_result passive FAIL "passive.curl.log"
    return
  }
  : >"${SECURITY_EVIDENCE_DIR}/sensitive-endpoints.tsv"
  for path in /actuator/env /actuator/configprops /actuator/beans /actuator/heapdump /actuator/threaddump /swagger-ui/index.html /v3/api-docs; do
    code="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' "${base}${path}" || true)"
    printf '%s\t%s\n' "${path}" "${code}" >>"${SECURITY_EVIDENCE_DIR}/sensitive-endpoints.tsv"
    case "${path}" in
      /actuator/env|/actuator/configprops|/actuator/beans|/actuator/heapdump|/actuator/threaddump)
        [[ "${code}" != "200" ]] || { record_result passive FAIL "sensitive-endpoints.tsv"; return; }
        ;;
    esac
  done
  record_result passive PASS "passive.headers,sensitive-endpoints.tsv"
}

run_nmap() {
  [[ "${SECURITY_RUN_NMAP}" == "true" ]] || return
  command -v nmap >/dev/null 2>&1 || { record_result external-port SKIPPED_TOOL nmap; return; }
  local target_host
  target_host="$(printf '%s' "${SECURITY_TARGET}" | sed -E 's#^https?://([^/]+).*$#\1#')"
  set +e
  nmap -sT -Pn --top-ports 100 --max-retries 1 --host-timeout 60s -oN "${SECURITY_EVIDENCE_DIR}/nmap.txt" "${target_host}" >"${SECURITY_EVIDENCE_DIR}/nmap.log" 2>&1
  local rc=$?
  set -e
  if ((rc != 0)); then
    record_result external-port FAIL nmap.log
  elif [[ ! -r "${SECURITY_PORT_RESULT_FILE}" ]]; then
    record_result external-port INCONCLUSIVE "SECURITY_PORT_RESULT_FILE missing"
  elif rg -q '^public_exposure_review=PASS$' "${SECURITY_PORT_RESULT_FILE}" && \
       rg -q '^unexpected_ports=0$' "${SECURITY_PORT_RESULT_FILE}" && \
       rg -q '^tls_review=PASS$' "${SECURITY_PORT_RESULT_FILE}"; then
    record_result external-port PASS "nmap.txt,${SECURITY_PORT_RESULT_FILE}"
  else
    record_result external-port FAIL "${SECURITY_PORT_RESULT_FILE}"
  fi
}

run_nuclei() {
  [[ "${SECURITY_RUN_NUCLEI}" == "true" ]] || return
  command -v nuclei >/dev/null 2>&1 || { record_result nuclei SKIPPED_TOOL nuclei; return; }
  set +e
  nuclei -u "${SECURITY_TARGET}" -severity medium,high,critical -rate-limit "${SECURITY_RATE_LIMIT}" -c 1 -jsonl -o "${SECURITY_EVIDENCE_DIR}/nuclei.jsonl" >"${SECURITY_EVIDENCE_DIR}/nuclei.log" 2>&1
  local rc=$?
  set -e
  if ((rc != 0)); then
    record_result nuclei FAIL nuclei.log
  elif [[ -s "${SECURITY_EVIDENCE_DIR}/nuclei.jsonl" ]]; then
    record_result nuclei FAIL nuclei.jsonl
  else
    record_result nuclei PASS nuclei.jsonl
  fi
}

run_authz() {
  [[ -n "${SECURITY_AUTHZ_CASE_FILE}" && -r "${SECURITY_AUTHZ_CASE_FILE}" ]] || { record_result authz INCONCLUSIVE "SECURITY_AUTHZ_CASE_FILE missing"; return; }
  local method path expected actual line_no=0 response_file
  while read -r method path expected; do
    line_no=$((line_no + 1))
    [[ -n "${method}" && "${method}" != \#* ]] || continue
    [[ "${method}" =~ ^(GET|POST|PUT|PATCH|DELETE)$ ]] || { record_result authz FAIL "case-line-${line_no}"; return; }
    [[ "${path}" == /* && "${path}" != *[[:space:]]* ]] || { record_result authz FAIL "case-line-${line_no}"; return; }
    [[ "${expected}" =~ ^[0-9]{3}(,[0-9]{3})*$ ]] || { record_result authz FAIL "case-line-${line_no}"; return; }
    response_file="${SECURITY_EVIDENCE_DIR}/authz-${line_no}.body"
    actual="$(curl --connect-timeout 3 --max-time 20 -sS -o "${response_file}" -w '%{http_code}' -X "${method}" "${SECURITY_TARGET%/}${path}" || true)"
    printf '%s\t%s\t%s\t%s\n' "${method}" "${path}" "${expected}" "${actual}" >>"${SECURITY_EVIDENCE_DIR}/authz-results.tsv"
    case ",${expected}," in *",${actual},"*) ;; *) record_result authz FAIL "authz-results.tsv"; return ;; esac
  done <"${SECURITY_AUTHZ_CASE_FILE}"
  ((line_no > 0)) || { record_result authz INCONCLUSIVE "authz case file is empty"; return; }
  record_result authz PASS "authz-results.tsv"
}

run_websocket() {
  [[ -n "${SECURITY_WS_URL}" && -n "${SECURITY_WS_USER_ID}" && -n "${SECURITY_WS_OTHER_USER_ID}" ]] || { record_result websocket INCONCLUSIVE "WS target/user fixture missing"; return; }
  set +e
  PRODUCT_LINE="${PRODUCT_LINE}" WS_URL="${SECURITY_WS_URL}" WS_USER_ID="${SECURITY_WS_USER_ID}" \
    WS_OTHER_USER_ID="${SECURITY_WS_OTHER_USER_ID}" WS_EVIDENCE="${SECURITY_EVIDENCE_DIR}/websocket-results.json" \
    node "${ROOT_DIR}/scripts/product-line-websocket-smoke.mjs" >"${SECURITY_EVIDENCE_DIR}/websocket.log" 2>&1
  local rc=$?
  set -e
  if ((rc == 0)); then record_result websocket PASS "websocket-results.json"; else record_result websocket FAIL "websocket.log"; fi
}

run_kafka() {
  local topics_cmd groups_cmd
  if command -v kafka-topics >/dev/null 2>&1; then topics_cmd=kafka-topics; elif command -v kafka-topics.sh >/dev/null 2>&1; then topics_cmd=kafka-topics.sh; else record_result kafka SKIPPED_TOOL kafka-topics; return; fi
  if command -v kafka-consumer-groups >/dev/null 2>&1; then groups_cmd=kafka-consumer-groups; elif command -v kafka-consumer-groups.sh >/dev/null 2>&1; then groups_cmd=kafka-consumer-groups.sh; else record_result kafka SKIPPED_TOOL kafka-consumer-groups; return; fi
  set +e
  "${topics_cmd}" --bootstrap-server "${SECURITY_KAFKA_BOOTSTRAP_SERVERS}" --list >"${SECURITY_EVIDENCE_DIR}/kafka-topics.txt" 2>"${SECURITY_EVIDENCE_DIR}/kafka-topics.error"
  local topics_rc=$?
  "${groups_cmd}" --bootstrap-server "${SECURITY_KAFKA_BOOTSTRAP_SERVERS}" --list >"${SECURITY_EVIDENCE_DIR}/kafka-groups.txt" 2>"${SECURITY_EVIDENCE_DIR}/kafka-groups.error"
  local groups_rc=$?
  set -e
  if ((topics_rc != 0 || groups_rc != 0)); then
    record_result kafka FAIL "kafka-topics.error,kafka-groups.error"
  elif [[ ! -r "${SECURITY_KAFKA_RESULT_FILE}" ]]; then
    record_result kafka INCONCLUSIVE "SECURITY_KAFKA_RESULT_FILE missing"
  else
    local required
    for required in unauthorized_write_rejected unauthorized_read_rejected key_mismatch_rejected cross_product_line_rejected replay_idempotent; do
      if ! rg -q "^${required}=PASS$" "${SECURITY_KAFKA_RESULT_FILE}"; then
        record_result kafka FAIL "${SECURITY_KAFKA_RESULT_FILE}"
        return
      fi
    done
    record_result kafka PASS "kafka-topics.txt,kafka-groups.txt,${SECURITY_KAFKA_RESULT_FILE}"
  fi
}

run_http_fuzz() {
  [[ "${SECURITY_RUN_FUZZ}" == "true" ]] || { record_result http-fuzz INCONCLUSIVE "SECURITY_RUN_FUZZ=false"; return; }
  [[ -n "${SECURITY_FUZZ_WORDLIST}" && -r "${SECURITY_FUZZ_WORDLIST}" ]] || { record_result http-fuzz INCONCLUSIVE "wordlist missing"; return; }
  command -v ffuf >/dev/null 2>&1 || { record_result http-fuzz SKIPPED_TOOL ffuf; return; }
  set +e
  ffuf -u "${SECURITY_TARGET%/}/FUZZ" -w "${SECURITY_FUZZ_WORDLIST}" -rate "${SECURITY_RATE_LIMIT}" -t 1 -mc 200,204,301,302,307,401,403 -of json -o "${SECURITY_EVIDENCE_DIR}/http-fuzz-results.json" >"${SECURITY_EVIDENCE_DIR}/http-fuzz.log" 2>&1
  local rc=$?
  set -e
  if ((rc != 0)); then
    record_result http-fuzz FAIL "http-fuzz.log"
  elif [[ ! -r "${SECURITY_FUZZ_RESULT_FILE}" ]]; then
    record_result http-fuzz INCONCLUSIVE "SECURITY_FUZZ_RESULT_FILE missing"
  elif rg -q '^resource_recovered=PASS$' "${SECURITY_FUZZ_RESULT_FILE}" && \
       rg -q '^business_facts_created=0$' "${SECURITY_FUZZ_RESULT_FILE}" && \
       rg -q '^authz_boundary=PASS$' "${SECURITY_FUZZ_RESULT_FILE}"; then
    record_result http-fuzz PASS "http-fuzz-results.json,${SECURITY_FUZZ_RESULT_FILE}"
  else
    record_result http-fuzz FAIL "${SECURITY_FUZZ_RESULT_FILE}"
  fi
}

run_concurrency() {
  [[ "${SECURITY_RUN_CONCURRENCY}" == "true" ]] || { record_result concurrency INCONCLUSIVE "SECURITY_RUN_CONCURRENCY=false"; return; }
  set +e
  PRODUCT_LINE="${PRODUCT_LINE}" SECURITY_EXECUTE=true SECURITY_AUTHORIZED=true \
    SECURITY_TARGET="${SECURITY_TARGET}" SECURITY_TARGET_ALLOWLIST="${SECURITY_TARGET_ALLOWLIST}" \
    SECURITY_CONCURRENCY_RESULT_FILE="${SECURITY_CONCURRENCY_RESULT_FILE}" \
    SECURITY_CONCURRENCY_EVIDENCE_DIR="${SECURITY_EVIDENCE_DIR}/concurrency" \
    "${ROOT_DIR}/scripts/security-concurrency-race.sh" >"${SECURITY_EVIDENCE_DIR}/concurrency.log" 2>&1
  local rc=$?
  set -e
  if ((rc == 0)); then record_result concurrency PASS "concurrency"; else record_result concurrency FAIL "concurrency.log"; fi
}

run_dependencies() {
  if command -v trivy >/dev/null 2>&1; then
    set +e
    trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --format json --output "${SECURITY_EVIDENCE_DIR}/dependencies.json" "${ROOT_DIR}" >"${SECURITY_EVIDENCE_DIR}/dependencies.log" 2>&1
    local rc=$?
    set -e
    if ((rc == 0)) && rg -q '"Severity"[[:space:]]*:[[:space:]]*"(HIGH|CRITICAL)"' "${SECURITY_EVIDENCE_DIR}/dependencies.json"; then
      record_result dependencies FAIL dependencies.json
    elif ((rc == 0)); then
      record_result dependencies PASS dependencies.json
    else
      record_result dependencies FAIL dependencies.log
    fi
  else
    record_result dependencies SKIPPED_TOOL trivy
  fi
}

run_funds_reconcile() {
  [[ "${SECURITY_RUN_FUNDS_RECONCILE}" == "true" ]] || { record_result funds-reconcile INCONCLUSIVE "SECURITY_RUN_FUNDS_RECONCILE=false"; return; }
  set +e
  PRODUCT_LINES="${PRODUCT_LINE}" "${ROOT_DIR}/scripts/product-line-funds-reconcile.sh" >"${SECURITY_EVIDENCE_DIR}/funds-reconcile.txt" 2>&1
  local rc=$?
  set -e
  if ((rc == 0)) && rg -q '\[funds-reconcile\] OK' "${SECURITY_EVIDENCE_DIR}/funds-reconcile.txt"; then record_result funds-reconcile PASS funds-reconcile.txt; else record_result funds-reconcile FAIL funds-reconcile.txt; fi
}

for scenario in ${SECURITY_SCENARIOS}; do
  case "${scenario}" in
    passive) run_passive; run_nmap ;;
    authz) run_authz ;;
    websocket) run_websocket ;;
    kafka) run_kafka ;;
    http-fuzz) run_http_fuzz; run_nuclei ;;
    resource) record_result resource INCONCLUSIVE "resource attack requires separate approved window" ;;
    concurrency) run_concurrency ;;
    dependencies) run_dependencies ;;
    canary) run_passive ;;
  esac
done
if [[ "${SECURITY_RUN_FUNDS_RECONCILE}" == "true" ]]; then
  run_funds_reconcile
elif [[ " ${SECURITY_SCENARIOS} " == *" concurrency "* ]]; then
  record_result funds-reconcile INCONCLUSIVE "required after concurrency scenarios"
fi

if [[ "${FAILED}" == "true" ]]; then
  echo "SECURITY SUITE FAIL evidence=${SECURITY_EVIDENCE_DIR}"
  exit 1
fi
echo "SECURITY SUITE PASS evidence=${SECURITY_EVIDENCE_DIR}"
