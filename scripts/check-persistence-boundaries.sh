#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

DB_CLIENT_PATTERN='JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|DSLContext'
MAIN_JAVA_PATTERN='/src/main/java/'
TABLES_FILE="$(mktemp)"
DB_FILES_FILE="$(mktemp)"
REPOSITORY_TOKENS_FILE="$(mktemp)"
REPOSITORY_TABLES_FILE="$(mktemp)"
trap 'rm -f "${TABLES_FILE}" "${DB_FILES_FILE}" "${REPOSITORY_TOKENS_FILE}" "${REPOSITORY_TABLES_FILE}"' EXIT

failure=0

# 从项目建表脚本生成物理表白名单，CTE、VALUES 和 generate_series 不会被误判为业务表。
rg --no-filename -o 'CREATE TABLE( IF NOT EXISTS)?[[:space:]]+[a-z_][a-z0-9_]*' \
    --glob '*.sql' \
    | awk '{print $NF}' \
    | sort -u > "${TABLES_FILE}"

rg -l "${DB_CLIENT_PATTERN}" --glob '*.java' \
    | rg "${MAIN_JAVA_PATTERN}" \
    | sort -u > "${DB_FILES_FILE}" || true

# JDBC 等数据库客户端只能出现在 Repository 实现中，禁止 Service、Controller 直接访问数据库。
while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    case "$(basename "${file}")" in
        *Repository.java)
            continue
            ;;
    esac
    if ! rg -q '@Repository' "${file}"; then
        echo "持久化边界违规：生产数据库访问不在 Repository 中：${file}" >&2
        failure=1
    fi
done < "${DB_FILES_FILE}"

# Repository 默认只能引用一张物理表；确实无法拆分时必须写明中文“不可拆原因”。
while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    tr -cs '[:alnum:]_' '\n' < "${file}" \
        | tr '[:upper:]' '[:lower:]' \
        | sort -u > "${REPOSITORY_TOKENS_FILE}"
    comm -12 "${TABLES_FILE}" "${REPOSITORY_TOKENS_FILE}" > "${REPOSITORY_TABLES_FILE}"
    table_count="$(wc -l < "${REPOSITORY_TABLES_FILE}" | tr -d '[:space:]')"
    if [[ "${table_count}" -gt 1 ]] && ! rg -q '不可拆原因' "${file}"; then
        tables="$(tr '\n' ',' < "${REPOSITORY_TABLES_FILE}" | sed 's/,$//')"
        echo "持久化边界违规：Repository 引用了多张物理表且未写“不可拆原因”：${file} [${tables}]" >&2
        failure=1
    fi
done < "${DB_FILES_FILE}"

if [[ "${failure}" -ne 0 ]]; then
    exit 1
fi

echo "持久化边界检查通过：生产数据库访问均位于 Repository，多表例外均已说明不可拆原因。"
