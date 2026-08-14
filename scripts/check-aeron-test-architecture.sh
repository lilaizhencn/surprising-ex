#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
execution_scripts=(
  "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh"
  "${ROOT_DIR}/scripts/run-six-product-line-acceptance.sh"
  "${ROOT_DIR}/scripts/run-uncapped-aeron-capacity.sh"
  "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh"
)

fail() {
  echo "AERON_TEST_ARCHITECTURE_FAIL: $*" >&2
  exit 1
}

for script in "${execution_scripts[@]}" \
    "${ROOT_DIR}/scripts/aeron-product-line-runtime.sh" \
    "${ROOT_DIR}/scripts/aeron-seed-balance.sh"; do
  bash -n "${script}" || fail "bash syntax: ${script}"
done

if rg -n 'INSERT INTO account_|UPDATE account_|perpetual-state/snapshot' \
    "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh" \
    "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh"; then
  fail "execution scripts still mutate PostgreSQL account authority"
fi

for component in InputBridgeMain ExporterMain ProjectionMain; do
  rg -q "${component}" "${ROOT_DIR}/scripts/aeron-product-line-runtime.sh" \
    || fail "shared runtime is missing ${component}"
  rg -q "${component}" "${ROOT_DIR}/scripts/run-uncapped-aeron-capacity.sh" \
    || fail "capacity runtime is missing ${component}"
done

for product_line in SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION; do
  rg -q "${product_line}" "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh" \
    || fail "API flow is missing ${product_line}"
  rg -q "${product_line}" "${ROOT_DIR}/scripts/aeron-product-line-runtime.sh" \
    || fail "runtime is missing ${product_line}"
done

rg -q 'core.inputs.v1' "${ROOT_DIR}/scripts/create-topics.sh" \
  || fail "Kafka Input Bridge topic is not created"
rg -q 'MANAGE_AERON_RUNTIME=true' "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" \
  || fail "P9 end-to-end gate does not own a complete Aeron runtime"
rg -q 'RUN_FUNCTIONAL_GATE.*true' "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" \
  || fail "P9 functional gate is not mandatory by default"
functional_line="$(rg -n 'run-six-product-line-acceptance\.sh' \
  "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" | cut -d: -f1 | head -n 1)"
capacity_line="$(rg -n 'run_case "\$\{product_line\}" capacity-step' \
  "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" | cut -d: -f1 | head -n 1)"
[[ -n "${functional_line}" && -n "${capacity_line}" && functional_line -lt capacity_line ]] \
  || fail "P9 capacity starts before the functional/funds gate"
rg -q 'SOAK_SECONDS=.*300' "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" \
  || fail "local soak must be five minutes"
rg -q 'SOAK_FAILOVER_AFTER_SECONDS=.*60' "${ROOT_DIR}/scripts/run-p9-six-line-capacity.sh" \
  || fail "leader failover must happen at 60 seconds"

echo "aeron-test-architecture=PASS product-lines=6 postgres-authority-writes=0"
