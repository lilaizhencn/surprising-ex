#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
RUNNER="$SCRIPT_DIR/../run.sh"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
RUNTIME_ROOT="${RUNTIME_ROOT:-${TMPDIR:-/tmp}/surprising-w3-w5-runtime}"
RUN_ID="${RUN_ID:-}"
PRODUCT_LINE="${PRODUCT_LINE:-}"
PRODUCT_LINES="${PRODUCT_LINES:-SPOT,LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION}"
WALLET_ENABLED="${WALLET_ENABLED:-false}"
W4_STATIC_ONLY="${W4_STATIC_ONLY:-false}"
W4_SCENARIO="${W4_SCENARIO:-w4-six-line}"

readonly REQUIRED_PRODUCT_LINES='SPOT,LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION'

fail() {
  printf 'ERROR=%s\n' "$*" >&2
  exit 2
}

require_boolean() {
  case "$2" in true|false) ;; *) fail "$1 must be true or false" ;; esac
}

validate() {
  [[ -n "$RUN_ID" ]] || fail 'RUN_ID_REQUIRED'
  [[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || fail "INVALID_RUN_ID runId=$RUN_ID"
  (( ${#RUN_ID} <= 50 )) || fail 'RUN_ID_TOO_LONG_FOR_LINE_SCOPES'
  [[ "$PRODUCT_LINE" == LINEAR_PERPETUAL ]] || fail "PRODUCT_LINE_REFUSED expected=LINEAR_PERPETUAL actual=${PRODUCT_LINE:-unset}"
  [[ "$PRODUCT_LINES" == "$REQUIRED_PRODUCT_LINES" ]] || fail "PRODUCT_LINES_REFUSED expected=$REQUIRED_PRODUCT_LINES actual=$PRODUCT_LINES"
  require_boolean WALLET_ENABLED "$WALLET_ENABLED"
  [[ "$WALLET_ENABLED" == false ]] || fail 'WALLET_REFUSED wallet must remain absent'
  require_boolean W4_STATIC_ONLY "$W4_STATIC_ONLY"
  [[ -x "$RUNNER" ]] || fail "RUNTIME_RUNNER_MISSING path=$RUNNER"
  [[ -x "$COMMON_SCRIPT" ]] || fail "COMMON_SCRIPT_MISSING path=$COMMON_SCRIPT"
  [[ -f "$REPO_ROOT/surprising-aeron-core/surprising-aeron-tools/pom.xml" ]] || fail 'TOOLS_POM_MISSING'
  [[ "${W4_LIFECYCLE_AUTHORITY:-CORE}" == CORE ]] || fail 'LIFECYCLE_AUTHORITY_REFUSED expected=CORE'
  [[ "${W4_PROJECTION_AUTHORITY:-CORE}" == CORE ]] || fail 'PROJECTION_AUTHORITY_REFUSED expected=CORE'
  [[ "${PG_SELECTED:-false}" == false ]] || fail 'PG_SELECTED_REFUSED'
}

required_rows() {
  case "$1" in
    SPOT)
      printf 'SPOT:CONSERVATION,SPOT:CONTROL_GUARD\n'
      ;;
    LINEAR_PERPETUAL|INVERSE_PERPETUAL)
      printf '%s:CROSS,%s:ISOLATED,FUNDING_POSITIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL\n' "$1" "$1"
      ;;
    LINEAR_DELIVERY|INVERSE_DELIVERY)
      printf '%s:CROSS,%s:ISOLATED,SETTLEMENT,CURSOR\n' "$1" "$1"
      ;;
    OPTION)
      printf 'OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM\n'
      ;;
    *)
      fail "PRODUCT_LINE_REFUSED line=$1"
      ;;
  esac
}

write_static_manifest() {
  local path="$1" line="$2"
  printf 'manifestVersion=1\nmode=STATIC_PREP\nproductLine=%s\nproviderProductLine=%s\ncoreProductLine=%s\nrows=%s\n' \
    "$line" "$line" "$line" "$(required_rows "$line")" > "$path"
  printf 'maker=REQUIRED\nwallet=ABSENT\nselectionAuthority=CORE\nprojectionAuthority=CORE\ncursorPolicy=MONOTONIC_NO_REPEAT_NO_GAP\nfundsReconciliation=NOT_RUN\nW4_STATUS=STATIC_PREP\n' >> "$path"
}

run_driver() {
  local line="$1" manifest="$2" seed="$3" mode="${4:-execute}"
  local java_bin="${JAVA_HOME:-}/bin/java"
  [[ -x "$java_bin" ]] || java_bin="$(command -v java || true)"
  [[ -n "$java_bin" && -x "$java_bin" ]] || fail 'JAVA_REQUIRED'
  local tool_jar="${W4_TOOL_JAR:-$REPO_ROOT/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar}"
  [[ -f "$tool_jar" ]] || fail "TOOLS_JAR_MISSING path=$tool_jar"
  PRODUCT_LINE="$line" WALLET_ENABLED=false "$java_bin" \
    -Dsurprising.aeron.product-line="$line" \
    -Dsurprising.aeron.w4-manifest="$manifest" \
    -Dsurprising.aeron.w4-seed="$seed" \
    -Dsurprising.aeron.w4-mode="$mode" \
    -cp "$tool_jar" com.surprising.aeron.tools.W4LifecycleQaMain
}

verify_manifest() {
  local line="$1" manifest="$2" mode="${3:-$([[ "$W4_STATIC_ONLY" == true ]] && printf STATIC_PREP || printf execute)}"
  local expected_rows row_count expected_status
  [[ -s "$manifest" ]] || fail "MANIFEST_MISSING productLine=$line path=$manifest"
  grep -q "^productLine=$line$" "$manifest" || fail "MANIFEST_PRODUCT_LINE_MISMATCH productLine=$line"
  grep -q "^providerProductLine=$line$" "$manifest" || fail "PROVIDER_PRODUCT_LINE_MISMATCH productLine=$line"
  grep -q "^coreProductLine=$line$" "$manifest" || fail "CORE_PRODUCT_LINE_MISMATCH productLine=$line"
  grep -q '^wallet=ABSENT$' "$manifest" || fail "WALLET_PRESENT productLine=$line"
  grep -q '^selectionAuthority=CORE$' "$manifest" || fail "LIFECYCLE_SELECTION_NOT_CORE productLine=$line"
  grep -q '^projectionAuthority=CORE$' "$manifest" || fail "PROJECTION_SELECTION_NOT_CORE productLine=$line"
  grep -q '^cursorPolicy=MONOTONIC_NO_REPEAT_NO_GAP$' "$manifest" || fail "CURSOR_POLICY_MISSING productLine=$line"
  expected_rows="$(required_rows "$line")"
  row_count="$(grep -c '^rows=' "$manifest" || true)"
  [[ "$row_count" == 1 ]] || fail "ROWS_COUNT productLine=$line expected=1 actual=$row_count"
  grep -Fxq "rows=$expected_rows" "$manifest" || fail "ROWS_MISMATCH productLine=$line expected=$expected_rows"
  case "$mode" in
    STATIC_PREP)
      grep -q '^mode=STATIC_PREP$' "$manifest" || fail "STATIC_MODE_MISSING productLine=$line"
      grep -q '^W4_STATUS=STATIC_PREP$' "$manifest" || fail "STATIC_STATUS_MISSING productLine=$line"
      grep -q '^fundsReconciliation=NOT_RUN$' "$manifest" || fail "STATIC_RECONCILIATION_MISSING productLine=$line"
      grep -q '^maker=REQUIRED$' "$manifest" || fail "MAKER_REQUIREMENT_MISSING productLine=$line"
      if grep -q '^FUNDS_DIFFERENCE=' "$manifest"; then
        fail "STATIC_FUNDS_DIFFERENCE_FORBIDDEN productLine=$line"
      fi
      ;;
    execute|verify|faults)
      case "$mode" in
        execute) expected_status=REAL_PASS ;;
        verify) expected_status=REAL_VERIFY_PASS ;;
        faults) expected_status=REAL_FAULTS_PASS ;;
      esac
      grep -q "^mode=$mode$" "$manifest" || fail "REAL_MODE_MISSING productLine=$line mode=$mode"
      grep -q "^W4_STATUS=$expected_status$" "$manifest" || fail "REAL_STATUS_MISSING productLine=$line mode=$mode"
      grep -q '^maker=OBSERVED$' "$manifest" || fail "MAKER_RECONCILIATION_MISSING productLine=$line"
      grep -q '^providerBoundary=OBSERVED$' "$manifest" || fail "PROVIDER_BOUNDARY_MISSING productLine=$line"
      grep -q '^fundsReconciliation=OBSERVED$' "$manifest" || fail "FUNDS_RECONCILIATION_MISSING productLine=$line"
      grep -q '^FUNDS_DIFFERENCE=0$' "$manifest" || fail "FUNDS_DIFFERENCE productLine=$line"
      ;;
    *)
      fail "MANIFEST_MODE_REFUSED productLine=$line mode=$mode"
      ;;
  esac
}

static_plan() {
  local plan_dir="$RUNTIME_ROOT/runs/$RUN_ID"
  mkdir -p "$plan_dir"
  {
    printf 'manifestVersion=1\nmode=STATIC_PREP\nproductLines=%s\nwallet=ABSENT\nmaker=REQUIRED\nselectionAuthority=CORE\nprojectionAuthority=CORE\n' "$PRODUCT_LINES"
    printf 'manifestOrder=1:SPOT,2:LINEAR_PERPETUAL,3:INVERSE_PERPETUAL,4:LINEAR_DELIVERY,5:INVERSE_DELIVERY,6:OPTION\n'
    printf 'SPOT=CONSERVATION,CONTROL_GUARD\n'
    printf 'PERPETUAL=LINEAR_PERPETUAL:CROSS,LINEAR_PERPETUAL:ISOLATED,INVERSE_PERPETUAL:CROSS,INVERSE_PERPETUAL:ISOLATED\n'
    printf 'DELIVERY=LINEAR_DELIVERY:CROSS,LINEAR_DELIVERY:ISOLATED,INVERSE_DELIVERY:CROSS,INVERSE_DELIVERY:ISOLATED\n'
    printf 'OPTION=CALL:ITM,CALL:ATM,CALL:OTM,PUT:ITM,PUT:ATM,PUT:OTM\n'
    printf 'W4_STATUS=STATIC_PREP\nfundsReconciliation=NOT_RUN\n'
  } > "$plan_dir/w4-six-line-static.manifest"
  printf 'W4_STATIC_PLAN=STATIC_PREP path=%s\n' "$plan_dir/w4-six-line-static.manifest"
}

run_six_lines() {
  source "$COMMON_SCRIPT"
  W4_RUN_DIR="$RUNTIME_ROOT/runs/$RUN_ID/w4-six-line"
  W4_ACTIVE_LINE=''
  W4_ACTIVE_RUN_ID=''
  W4_RUNNER="$RUNNER"
  W4_LINE_INDEX=0
  W4_STATIC_ONLY="$W4_STATIC_ONLY"
  mkdir -p "$W4_RUN_DIR/manifests"
  local status=0 line manifest seed
  trap 'status=$?; if [[ -n "${W4_ACTIVE_LINE:-}" ]]; then stop_line_subset "$W4_ACTIVE_LINE" || status=1; fi; exit "$status"' EXIT
  IFS=',' read -r -a lines <<< "$PRODUCT_LINES"
  for line in "${lines[@]}"; do
    W4_LINE_INDEX=$((W4_LINE_INDEX + 1))
    manifest="$W4_RUN_DIR/manifests/${W4_LINE_INDEX}-${line}.manifest"
    seed=$((16000 + W4_LINE_INDEX))
    start_line_subset "$line"
    if [[ "$W4_STATIC_ONLY" == true ]]; then
      write_static_manifest "$manifest" "$line"
    else
      run_driver "$line" "$manifest" "$seed"
    fi
    verify_manifest "$line" "$manifest" "$([[ "$W4_STATIC_ONLY" == true ]] && printf STATIC_PREP || printf execute)"
    stop_line_subset "$line"
  done
  trap - EXIT
  local count
  count="$(find "$W4_RUN_DIR/manifests" -type f -name '*.manifest' | wc -l | tr -d ' ')"
  [[ "$count" == 6 ]] || fail "MANIFEST_COUNT expected=6 actual=$count"
  local expected_names actual_names
  expected_names='1-SPOT.manifest 2-LINEAR_PERPETUAL.manifest 3-INVERSE_PERPETUAL.manifest 4-LINEAR_DELIVERY.manifest 5-INVERSE_DELIVERY.manifest 6-OPTION.manifest'
  actual_names="$(find "$W4_RUN_DIR/manifests" -type f -name '*.manifest' -exec basename {} \; | sort | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  [[ "$actual_names" == "$expected_names" ]] || fail "MANIFEST_ORDER expected=$expected_names actual=$actual_names"
  if [[ "$W4_STATIC_ONLY" == true ]]; then
    printf 'W4_SIX_LINE=STATIC_PREP order=%s manifests=%s wallet=ABSENT maker=REQUIRED cleanup=STATIC_PREP\n' \
      "$PRODUCT_LINES" "$W4_RUN_DIR/manifests"
  else
    printf 'W4_SIX_LINE=PASS order=%s manifests=%s wallet=ABSENT maker=OBSERVED cleanup=PASS\n' \
      "$PRODUCT_LINES" "$W4_RUN_DIR/manifests"
  fi
}

run_faults() {
  source "$COMMON_SCRIPT"
  W4_RUN_DIR="$RUNTIME_ROOT/runs/$RUN_ID/w4-faults"
  W4_ACTIVE_LINE=''
  W4_ACTIVE_RUN_ID=''
  W4_RUNNER="$RUNNER"
  W4_LINE_INDEX=0
  mkdir -p "$W4_RUN_DIR/manifests"
  trap 'status=$?; if [[ -n "${W4_ACTIVE_LINE:-}" ]]; then stop_line_subset "$W4_ACTIVE_LINE" || status=1; fi; exit "$status"' EXIT
  W4_LINE_INDEX=1
  local line=LINEAR_PERPETUAL manifest="$W4_RUN_DIR/manifests/1-LINEAR_PERPETUAL-faults.manifest"
  start_line_subset "$line"
  if [[ "$W4_STATIC_ONLY" == true ]]; then
    write_static_manifest "$manifest" "$line"
    printf 'faults=NOT_EXERCISED\n' >> "$manifest"
  else
    run_driver "$line" "$manifest" 16999 faults
  fi
  verify_manifest "$line" "$manifest" "$([[ "$W4_STATIC_ONLY" == true ]] && printf STATIC_PREP || printf faults)"
  stop_line_subset "$line"
  trap - EXIT
  if [[ "$W4_STATIC_ONLY" == true ]]; then
    printf 'W4_FAULTS=STATIC_PREP productLine=%s cleanup=STATIC_PREP wallet=ABSENT faults=NOT_EXERCISED\n' "$line"
  else
    printf 'W4_FAULTS=PASS productLine=%s cleanup=PASS wallet=ABSENT\n' "$line"
  fi
}

main() {
  validate
  if [[ "$W4_SCENARIO" == w4-faults ]]; then
    run_faults
    return 0
  fi
  if [[ "$W4_STATIC_ONLY" == true ]]; then
    static_plan
  fi
  run_six_lines
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
