#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNTIME_DIR/../../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w3w5-topic-provisioning.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT INT TERM

extract_function() {
  local name="$1" script="$2"
  awk -v name="$name" '
    $0 ~ "^" name "\\(\\) \\{" { in_function=1 }
    in_function { print }
    in_function && /^}$/ { exit }
  ' "$script"
}

run_case() {
  local script="$1" case_name provisioned metadata_calls metadata_args
  local expected actual missing expected_count actual_count metadata_call_count metadata_command
  case_name="$(basename "$script" .sh)"
  provisioned="$TEST_ROOT/$case_name.provisioned"
  metadata_calls="$TEST_ROOT/$case_name.metadata-calls"
  metadata_args="$TEST_ROOT/$case_name.metadata-args"
  : > "$provisioned"
  printf '0\n' > "$metadata_calls"
  : > "$metadata_args"

  export REPO_ROOT PROVISIONED_FILE="$provisioned" METADATA_CALLS_FILE="$metadata_calls" METADATA_ARGS_FILE="$metadata_args"
  export PRODUCT_TOPIC_SOURCE="$REPO_ROOT/surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java"
  eval "$(extract_function topic_list "$script")"

  emit_topic_names() {
    local topic
    while IFS= read -r topic; do
      [[ "${METADATA_MODE:-valid}" == missing && "$topic" == surprising.linear-perp.core.events.v1 ]] && continue
      printf '%s\n' "$topic"
    done < <(topic_list)
    [[ "${METADATA_MODE:-valid}" != extra ]] || printf 'surprising.linear-perp.unexpected.events.v1\n'
  }

  emit_topic_metadata() {
    local filter="$1" topic partitions
    while IFS= read -r topic; do
      [[ "${METADATA_MODE:-valid}" == missing && "$topic" == surprising.linear-perp.core.events.v1 ]] && continue
      [[ -z "$filter" || "$filter" == "$topic" ]] || continue
      partitions=1
      [[ "${METADATA_MODE:-valid}" != multi || "$topic" != surprising.linear-perp.core.events.v1 ]] || partitions=2
      printf 'Topic: %s TopicId: fixture PartitionCount: %s ReplicationFactor: 1 Configs:\n' "$topic" "$partitions"
      printf '\tTopic: %s Partition: 0 Leader: 1 Replicas: 1 Isr: 1\n' "$topic"
    done < <(topic_list)
    if [[ "${METADATA_MODE:-valid}" == extra && -z "$filter" ]]; then
      printf 'Topic: surprising.linear-perp.unexpected.events.v1 TopicId: fixture PartitionCount: 1 ReplicationFactor: 1 Configs:\n'
      printf '\tTopic: surprising.linear-perp.unexpected.events.v1 Partition: 0 Leader: 1 Replicas: 1 Isr: 1\n'
    fi
  }

  record_metadata_call() {
    local count
    count="$(<"$METADATA_CALLS_FILE")"
    printf '%s\n' "$((count + 1))" > "$METADATA_CALLS_FILE"
    printf '%s\n' "$1" >> "$METADATA_ARGS_FILE"
  }

  compose() {
    local args="$*" topic='' previous=''
    while (($#)); do
      [[ "$previous" == --topic ]] && topic="$1"
      previous="$1"
      shift
    done
    if [[ "$args" == *--create* ]]; then
      printf '%s\n' "$topic" >> "$PROVISIONED_FILE"
      cat >/dev/null
    elif [[ "$args" == *--list* ]]; then
      record_metadata_call "$args"
      emit_topic_names | LC_ALL=C sort
    elif [[ "$args" == *--describe* ]]; then
      record_metadata_call "$args"
      [[ "${METADATA_MODE:-valid}" != command-fail ]] || return 255
      emit_topic_metadata "$topic"
    else
      cat >/dev/null
    fi
  }

  fail() { printf 'ERROR=%s\n' "$*" >&2; exit 2; }
  mark_ready() { :; }
  eval "$(extract_function verify_topics "$script")"
  eval "$(extract_function create_topics "$script")"
  METADATA_MODE=valid
  create_topics

  expected="$(topic_list | LC_ALL=C sort)"
  actual="$(LC_ALL=C sort -u "$PROVISIONED_FILE")"
  expected_count="$(printf '%s\n' "$expected" | awk 'NF { count++ } END { print count + 0 }')"
  actual_count="$(printf '%s\n' "$actual" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ "$actual" != "$expected" ]]; then
    missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | paste -sd, -)"
    printf 'FAIL every ProductTopicNames topic must be provisioned even when the invoked create command consumes stdin: script=%s expected=%s actual=%s missing=%s\n' \
      "$case_name" "$expected_count" "$actual_count" "${missing:-none}" >&2
    return 1
  fi
  metadata_call_count="$(<"$metadata_calls")"
  if [[ "$metadata_call_count" != 1 ]]; then
    printf 'FAIL topic verification must use one bounded Kafka CLI metadata call instead of N external execs: script=%s expectedCalls=1 actualCalls=%s\n' \
      "$case_name" "$metadata_call_count" >&2
    return 1
  fi
  metadata_command="$(<"$metadata_args")"
  if [[ "$metadata_command" != *'timeout 30s'* || "$metadata_command" != *--describe* || "$metadata_command" == *--topic* ]]; then
    printf 'FAIL topic verification metadata call must be bounded and describe all topics once: script=%s command=%s\n' \
      "$case_name" "$metadata_command" >&2
    return 1
  fi
  printf 'PASS every ProductTopicNames topic is provisioned even when the invoked create command consumes stdin: script=%s topics=%s partitions=1\n' \
    "$case_name" "$expected_count"

  expect_metadata_refusal() {
    local mode="$1" expected_error="${2:-KAFKA_TOPIC_METADATA_MISMATCH}" output status calls
    printf '0\n' > "$metadata_calls"
    : > "$metadata_args"
    set +e
    output="$(METADATA_MODE="$mode" verify_topics 2>&1)"
    status=$?
    set -e
    calls="$(<"$metadata_calls")"
    if [[ "$status" == 0 || "$output" != *"$expected_error"* || "$calls" != 1 ]]; then
      printf 'FAIL topic metadata parser must fail closed: script=%s mode=%s status=%s calls=%s output=%s\n' \
        "$case_name" "$mode" "$status" "$calls" "$output" >&2
      return 1
    fi
    printf 'PASS topic metadata parser fails closed: script=%s mode=%s calls=1\n' "$case_name" "$mode"
  }

  expect_metadata_refusal missing
  expect_metadata_refusal extra
  expect_metadata_refusal multi
  expect_metadata_refusal command-fail KAFKA_TOPIC_METADATA_COMMAND_FAILED
}

run_case "$RUNTIME_DIR/run.sh"
run_case "$RUNTIME_DIR/scenarios/w5-export-projection.sh"
printf 'TOPIC_PROVISIONING_STDIN_SAFE=PASS scripts=2\n'
