#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

CONTROLLER_FILES="$(mktemp)"
SCHEDULED_FILES="$(mktemp)"
TASK_FILES="$(mktemp)"
trap 'rm -f "${CONTROLLER_FILES}" "${SCHEDULED_FILES}" "${TASK_FILES}"' EXIT

failure=0

rg --files \
    | rg '/src/main/java/.+Controller\.java$' \
    | sort -u > "${CONTROLLER_FILES}" || true

rg -l '@Scheduled' \
    --glob '*.java' \
    --glob '!**/target/**' \
    | rg '/src/main/java/' \
    | sort -u > "${SCHEDULED_FILES}" || true

rg --files \
    | rg '/src/main/java/.+/task/.+\.java$' \
    | sort -u > "${TASK_FILES}" || true

# Controller 只负责协议入口，不得越过 Service 直接访问持久化设施。
while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    if rg -q \
        'import .+\.repository\.|JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|DSLContext|RedisTemplate|StringRedisTemplate' \
        "${file}"; then
        echo "入口边界违规：Controller 直接依赖 Repository 或数据客户端：${file}" >&2
        failure=1
    fi
    if rg -q '@Scheduled|@Transactional|@KafkaListener' "${file}"; then
        echo "入口边界违规：Controller 承担了调度、事务或消息消费职责：${file}" >&2
        failure=1
    fi
done < "${CONTROLLER_FILES}"

# 所有 Spring 定时入口必须放在 task 包中，业务执行仍由 Service 完成。
while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    if [[ "${file}" != */task/* ]]; then
        echo "入口边界违规：@Scheduled 不在 task 包中：${file}" >&2
        failure=1
    fi
done < "${SCHEDULED_FILES}"

# Task 只能编排触发时机并委托 Service，不得直接访问 Repository、缓存或外部客户端。
while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    if rg -q \
        'import .+\.repository\.|import .+\.client\.|JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|DSLContext|RedisTemplate|StringRedisTemplate' \
        "${file}"; then
        echo "入口边界违规：Task 直接依赖 Repository、缓存或外部客户端：${file}" >&2
        failure=1
    fi
    if rg -q '@Transactional|@KafkaListener' "${file}"; then
        echo "入口边界违规：Task 承担了事务或消息消费职责：${file}" >&2
        failure=1
    fi
    if ! rg -q '^import com\.surprising\..+\.service\.' "${file}"; then
        echo "入口边界违规：Task 未委托 Service：${file}" >&2
        failure=1
    fi
done < "${TASK_FILES}"

if [[ "${failure}" -ne 0 ]]; then
    exit 1
fi

echo "入口边界检查通过：Controller 仅作为协议入口，定时任务均位于 task 包并委托 Service。"
