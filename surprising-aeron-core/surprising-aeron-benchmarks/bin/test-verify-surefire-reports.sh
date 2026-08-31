#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="${SCRIPT_DIR}/verify-surefire-reports.sh"
FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "${FIXTURE_DIR}"' EXIT

write_suite() {
  local path="$1" name="$2" tests="${3:-1}" failures="${4:-0}" errors="${5:-0}" skipped="${6:-0}"
  mkdir -p "$(dirname "${path}")"
  printf '<testsuite name="%s" tests="%s" failures="%s" errors="%s" skipped="%s"></testsuite>\n' \
    "${name}" "${tests}" "${failures}" "${errors}" "${skipped}" > "${path}"
}

expect_reject() {
  local scenario="$1"
  shift
  if "$@" > "${FIXTURE_DIR}/${scenario}.stdout" 2> "${FIXTURE_DIR}/${scenario}.stderr"; then
    echo "fixture unexpectedly accepted: ${scenario}" >&2
    exit 1
  fi
  [[ -s "${FIXTURE_DIR}/${scenario}.stderr" ]]
  printf 'PASS reject %s: %s\n' "${scenario}" "$(head -n 1 "${FIXTURE_DIR}/${scenario}.stderr")"
}

mkdir -p "${FIXTURE_DIR}/stale" "${FIXTURE_DIR}/suffix" "${FIXTURE_DIR}/multi" \
  "${FIXTURE_DIR}/missing" "${FIXTURE_DIR}/fresh"

write_suite "${FIXTURE_DIR}/stale/TEST-stale.xml" com.example.StaleGreenTest
touch -t 202001010000 "${FIXTURE_DIR}/stale/TEST-stale.xml"
touch -t 202101010000 "${FIXTURE_DIR}/stale.start"
expect_reject stale-green bash "${VERIFIER}" "${FIXTURE_DIR}/stale" StaleGreenTest \
  "${FIXTURE_DIR}/stale.start" "${FIXTURE_DIR}/stale.tsv"

write_suite "${FIXTURE_DIR}/suffix/TEST-collision.xml" com.example.NotFreshExactTest
touch -t 202001010000 "${FIXTURE_DIR}/suffix.start"
expect_reject suffix-collision bash "${VERIFIER}" "${FIXTURE_DIR}/suffix" FreshExactTest \
  "${FIXTURE_DIR}/suffix.start" "${FIXTURE_DIR}/suffix.tsv"

write_suite "${FIXTURE_DIR}/multi/TEST-first.xml" com.example.MultiMatchTest
write_suite "${FIXTURE_DIR}/multi/TEST-second.xml" com.example.MultiMatchTest
touch -t 202001010000 "${FIXTURE_DIR}/multi.start"
expect_reject multi-match bash "${VERIFIER}" "${FIXTURE_DIR}/multi" com.example.MultiMatchTest \
  "${FIXTURE_DIR}/multi.start" "${FIXTURE_DIR}/multi.tsv"

touch -t 202001010000 "${FIXTURE_DIR}/missing.start"
expect_reject missing bash "${VERIFIER}" "${FIXTURE_DIR}/missing" MissingTest \
  "${FIXTURE_DIR}/missing.start" "${FIXTURE_DIR}/missing.tsv"

write_suite "${FIXTURE_DIR}/fresh/TEST-fqcn.xml" com.example.FreshExactTest 2
write_suite "${FIXTURE_DIR}/fresh/TEST-simple.xml" org.example.SimpleExactTest 3
touch -t 202001010000 "${FIXTURE_DIR}/fresh.start"
bash "${VERIFIER}" "${FIXTURE_DIR}/fresh" \
  com.example.FreshExactTest,SimpleExactTest "${FIXTURE_DIR}/fresh.start" "${FIXTURE_DIR}/fresh.tsv"
[[ "$(wc -l < "${FIXTURE_DIR}/fresh.tsv")" -eq 3 ]]
grep -q $'^com.example.FreshExactTest\t2\t0\t0\t0\tcom.example.FreshExactTest\t' \
  "${FIXTURE_DIR}/fresh.tsv"
grep -q $'^SimpleExactTest\t3\t0\t0\t0\torg.example.SimpleExactTest\t' \
  "${FIXTURE_DIR}/fresh.tsv"
echo 'PASS accept fresh exact FQCN and simple class reports'
