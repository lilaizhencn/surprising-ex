#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="${DB_NAME:-surprising_product_line_smoke}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
PRODUCT_LINES="${PRODUCT_LINES:-LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT}"
STRICT_ZERO_OPENING="${STRICT_ZERO_OPENING:-true}"
MAX_REPORT_ROWS="${MAX_REPORT_ROWS:-200}"

if ! [[ "${MAX_REPORT_ROWS}" =~ ^[0-9]+$ ]] || ((MAX_REPORT_ROWS < 1)); then
  echo "MAX_REPORT_ROWS must be a positive integer" >&2
  exit 1
fi

STRICT_ZERO_OPENING_NORMALIZED="$(printf '%s' "${STRICT_ZERO_OPENING}" | tr '[:upper:]' '[:lower:]')"
case "${STRICT_ZERO_OPENING_NORMALIZED}" in
  true|1|yes) STRICT_ZERO_OPENING_SQL="true" ;;
  false|0|no) STRICT_ZERO_OPENING_SQL="false" ;;
  *)
    echo "STRICT_ZERO_OPENING must be true or false" >&2
    exit 1
    ;;
esac

target_inserts=""

append_product_line() {
  local product_line="$1"
  local account_type contract_type uses_legacy margin_product funding_product spot_product
  case "${product_line}" in
    SPOT)
      account_type="SPOT"
      contract_type="SPOT"
      uses_legacy="false"
      margin_product="false"
      funding_product="false"
      spot_product="true"
      ;;
    LINEAR_PERPETUAL)
      account_type="USDT_PERPETUAL"
      contract_type="LINEAR_PERPETUAL"
      uses_legacy="true"
      margin_product="true"
      funding_product="true"
      spot_product="false"
      ;;
    INVERSE_PERPETUAL)
      account_type="COIN_PERPETUAL"
      contract_type="INVERSE_PERPETUAL"
      uses_legacy="false"
      margin_product="true"
      funding_product="true"
      spot_product="false"
      ;;
    LINEAR_DELIVERY)
      account_type="USDT_DELIVERY"
      contract_type="LINEAR_DELIVERY"
      uses_legacy="false"
      margin_product="true"
      funding_product="false"
      spot_product="false"
      ;;
    INVERSE_DELIVERY)
      account_type="COIN_DELIVERY"
      contract_type="INVERSE_DELIVERY"
      uses_legacy="false"
      margin_product="true"
      funding_product="false"
      spot_product="false"
      ;;
    OPTION)
      account_type="OPTION"
      contract_type="VANILLA_OPTION"
      uses_legacy="false"
      margin_product="true"
      funding_product="false"
      spot_product="false"
      ;;
    *)
      echo "Unsupported product line: ${product_line}" >&2
      exit 1
      ;;
  esac
  target_inserts+=$'\n'
  target_inserts+="INSERT INTO target_product_lines VALUES ('${product_line}', '${account_type}', '${contract_type}', ${uses_legacy}, ${margin_product}, ${funding_product}, ${spot_product});"
}

for product_line in ${PRODUCT_LINES}; do
  append_product_line "${product_line}"
done

if [[ -z "${target_inserts}" ]]; then
  echo "PRODUCT_LINES must contain at least one product line" >&2
  exit 1
fi

psql_exec() {
  PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${POSTGRES_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
    -q -v ON_ERROR_STOP=1 -P pager=off "$@"
}

echo "[funds-reconcile] database=${DB_NAME} product_lines=${PRODUCT_LINES}"

psql_exec <<SQL
CREATE TEMP TABLE target_product_lines (
    product_line text PRIMARY KEY,
    account_type text NOT NULL,
    contract_type text NOT NULL,
    uses_legacy boolean NOT NULL,
    margin_product boolean NOT NULL,
    funding_product boolean NOT NULL,
    spot_product boolean NOT NULL
);
${target_inserts}

CREATE TEMP TABLE reconcile_violations (
    area text NOT NULL,
    detail text NOT NULL
);

CREATE TEMP TABLE product_ledger_scope AS
SELECT t.product_line,
       l.entry_id,
       l.user_id,
       l.account_type,
       l.asset,
       l.amount_units,
       l.balance_after_units,
       l.reference_type,
       l.reference_id,
       l.reason,
       l.symbol,
       l.created_at
  FROM account_product_ledger_entries l
  JOIN target_product_lines t
    ON t.account_type = l.account_type
   AND NOT t.uses_legacy;

CREATE TEMP TABLE legacy_ledger_scope AS
SELECT t.product_line,
       t.account_type,
       l.entry_id,
       l.user_id,
       l.asset,
       l.amount_units,
       l.balance_after_units,
       l.reference_type,
       l.reference_id,
       l.reason,
       l.trade_id,
       l.order_id,
       l.symbol,
       l.fee_rate_ppm,
       l.created_at
  FROM account_ledger_entries l
 CROSS JOIN target_product_lines t
 WHERE t.uses_legacy;

CREATE TEMP TABLE product_balance_scope AS
SELECT t.product_line,
       b.account_type,
       b.user_id,
       b.asset,
       b.available_units,
       b.locked_units,
       COALESCE(d.deficit_units, 0) AS deficit_units,
       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS balance_units
  FROM account_product_balances b
  JOIN target_product_lines t
    ON t.account_type = b.account_type
   AND NOT t.uses_legacy
  LEFT JOIN account_product_deficits d
    ON d.account_type = b.account_type
   AND d.user_id = b.user_id
   AND d.asset = b.asset;

CREATE TEMP TABLE legacy_balance_scope AS
SELECT t.product_line,
       t.account_type,
       b.user_id,
       b.asset,
       b.available_units,
       b.locked_units,
       COALESCE(d.deficit_units, 0) AS deficit_units,
       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS balance_units
  FROM account_balances b
 CROSS JOIN target_product_lines t
  LEFT JOIN account_deficits d
    ON d.user_id = b.user_id
   AND d.asset = b.asset
 WHERE t.uses_legacy;

CREATE TEMP TABLE ledger_scope AS
SELECT 'PRODUCT'::text AS ledger_scope,
       product_line,
       account_type,
       entry_id,
       user_id,
       asset,
       amount_units,
       balance_after_units,
       reference_type,
       reference_id,
       reason,
       symbol,
       NULL::bigint AS trade_id,
       NULL::bigint AS order_id,
       NULL::bigint AS fee_rate_ppm,
       created_at
  FROM product_ledger_scope
UNION ALL
SELECT 'LEGACY'::text AS ledger_scope,
       product_line,
       account_type,
       entry_id,
       user_id,
       asset,
       amount_units,
       balance_after_units,
       reference_type,
       reference_id,
       reason,
       symbol,
       trade_id,
       order_id,
       fee_rate_ppm,
       created_at
  FROM legacy_ledger_scope;

CREATE TEMP TABLE balance_scope AS
SELECT 'PRODUCT'::text AS ledger_scope,
       product_line,
       account_type,
       user_id,
       asset,
       available_units,
       locked_units,
       deficit_units,
       balance_units
  FROM product_balance_scope
UNION ALL
SELECT 'LEGACY'::text AS ledger_scope,
       product_line,
       account_type,
       user_id,
       asset,
       available_units,
       locked_units,
       deficit_units,
       balance_units
  FROM legacy_balance_scope;

CREATE TEMP TABLE match_trade_order_refs AS
SELECT mt.product_line,
       mt.trade_id,
       mt.taker_order_id AS order_id,
       mt.taker_user_id AS user_id
  FROM trading_match_trades mt
  JOIN target_product_lines t
    ON t.product_line = mt.product_line
UNION ALL
SELECT mt.product_line,
       mt.trade_id,
       mt.maker_order_id AS order_id,
       mt.maker_user_id AS user_id
  FROM trading_match_trades mt
  JOIN target_product_lines t
    ON t.product_line = mt.product_line;

CREATE INDEX idx_product_ledger_scope_account_asset_entry
    ON product_ledger_scope(account_type, user_id, asset, entry_id);
CREATE INDEX idx_legacy_ledger_scope_account_asset_entry
    ON legacy_ledger_scope(user_id, asset, entry_id);
CREATE INDEX idx_product_ledger_scope_fee_reference
    ON product_ledger_scope(reference_type, reference_id);
CREATE INDEX idx_legacy_ledger_scope_fee_reference
    ON legacy_ledger_scope(reference_type, trade_id, order_id, user_id);
CREATE INDEX idx_match_trade_order_refs_lookup
    ON match_trade_order_refs(product_line, trade_id, order_id, user_id);

ANALYZE product_ledger_scope;
ANALYZE legacy_ledger_scope;
ANALYZE product_balance_scope;
ANALYZE legacy_balance_scope;
ANALYZE match_trade_order_refs;

INSERT INTO reconcile_violations(area, detail)
SELECT 'unexpected_legacy_product_rows',
       format('product_line=%s account_type=%s user=%s asset=%s has product-account row for legacy-ledger product line',
              t.product_line, b.account_type, b.user_id, b.asset)
  FROM account_product_balances b
  JOIN target_product_lines t
    ON t.account_type = b.account_type
   AND t.uses_legacy
 WHERE b.available_units <> 0
    OR b.locked_units <> 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'unexpected_legacy_product_ledger',
       format('product_line=%s account_type=%s entry_id=%s user=%s asset=%s reference=%s:%s',
              t.product_line, l.account_type, l.entry_id, l.user_id, l.asset, l.reference_type, l.reference_id)
  FROM account_product_ledger_entries l
  JOIN target_product_lines t
    ON t.account_type = l.account_type
   AND t.uses_legacy;

INSERT INTO reconcile_violations(area, detail)
SELECT 'negative_product_balance',
       format('product_line=%s account_type=%s user=%s asset=%s available=%s locked=%s',
              product_line, account_type, user_id, asset, available_units, locked_units)
  FROM product_balance_scope
 WHERE available_units < 0
    OR locked_units < 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'negative_legacy_balance',
       format('product_line=%s account_type=%s user=%s asset=%s available=%s locked=%s',
              product_line, account_type, user_id, asset, available_units, locked_units)
  FROM legacy_balance_scope
 WHERE available_units < 0
    OR locked_units < 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'negative_product_deficit',
       format('account_type=%s user=%s asset=%s deficit=%s', d.account_type, d.user_id, d.asset, d.deficit_units)
  FROM account_product_deficits d
  JOIN target_product_lines t
    ON t.account_type = d.account_type
   AND NOT t.uses_legacy
 WHERE d.deficit_units < 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'negative_legacy_deficit',
       format('user=%s asset=%s deficit=%s', d.user_id, d.asset, d.deficit_units)
  FROM account_deficits d
 WHERE d.deficit_units < 0
   AND EXISTS (SELECT 1 FROM target_product_lines WHERE uses_legacy);

INSERT INTO reconcile_violations(area, detail)
SELECT 'orphan_product_deficit',
       format('account_type=%s user=%s asset=%s deficit=%s has no balance row',
              d.account_type, d.user_id, d.asset, d.deficit_units)
  FROM account_product_deficits d
  JOIN target_product_lines t
    ON t.account_type = d.account_type
   AND NOT t.uses_legacy
  LEFT JOIN account_product_balances b
    ON b.account_type = d.account_type
   AND b.user_id = d.user_id
   AND b.asset = d.asset
 WHERE d.deficit_units <> 0
   AND b.user_id IS NULL;

INSERT INTO reconcile_violations(area, detail)
SELECT 'orphan_legacy_deficit',
       format('user=%s asset=%s deficit=%s has no balance row', d.user_id, d.asset, d.deficit_units)
  FROM account_deficits d
  LEFT JOIN account_balances b
    ON b.user_id = d.user_id
   AND b.asset = d.asset
 WHERE d.deficit_units <> 0
   AND b.user_id IS NULL
   AND EXISTS (SELECT 1 FROM target_product_lines WHERE uses_legacy);

WITH ordered AS (
    SELECT l.*,
           SUM(amount_units) OVER (
               PARTITION BY account_type, user_id, asset
               ORDER BY entry_id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           ) AS running_units,
           FIRST_VALUE(balance_after_units - amount_units) OVER (
               PARTITION BY account_type, user_id, asset
               ORDER BY entry_id
           ) AS opening_units
      FROM product_ledger_scope l
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'product_ledger_continuity',
       format('account_type=%s user=%s asset=%s entry_id=%s expected_balance_after=%s actual_balance_after=%s reference=%s:%s',
              account_type, user_id, asset, entry_id, opening_units + running_units,
              balance_after_units, reference_type, reference_id)
  FROM ordered
 WHERE balance_after_units <> opening_units + running_units;

WITH ordered AS (
    SELECT l.*,
           FIRST_VALUE(balance_after_units - amount_units) OVER (
               PARTITION BY account_type, user_id, asset
               ORDER BY entry_id
           ) AS opening_units,
           ROW_NUMBER() OVER (
               PARTITION BY account_type, user_id, asset
               ORDER BY entry_id
           ) AS rn
      FROM product_ledger_scope l
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'product_non_zero_opening',
       format('account_type=%s user=%s asset=%s opening_units=%s first_entry_id=%s',
              account_type, user_id, asset, opening_units, entry_id)
  FROM ordered
 WHERE ${STRICT_ZERO_OPENING_SQL}
   AND rn = 1
   AND opening_units <> 0;

WITH ordered AS (
    SELECT l.*,
           SUM(amount_units) OVER (
               PARTITION BY user_id, asset
               ORDER BY entry_id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           ) AS running_units,
           FIRST_VALUE(balance_after_units - amount_units) OVER (
               PARTITION BY user_id, asset
               ORDER BY entry_id
           ) AS opening_units
      FROM legacy_ledger_scope l
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'legacy_ledger_continuity',
       format('account_type=%s user=%s asset=%s entry_id=%s expected_balance_after=%s actual_balance_after=%s reference=%s:%s',
              account_type, user_id, asset, entry_id, opening_units + running_units,
              balance_after_units, reference_type, reference_id)
  FROM ordered
 WHERE balance_after_units <> opening_units + running_units;

WITH ordered AS (
    SELECT l.*,
           FIRST_VALUE(balance_after_units - amount_units) OVER (
               PARTITION BY user_id, asset
               ORDER BY entry_id
           ) AS opening_units,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, asset
               ORDER BY entry_id
           ) AS rn
      FROM legacy_ledger_scope l
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'legacy_non_zero_opening',
       format('account_type=%s user=%s asset=%s opening_units=%s first_entry_id=%s',
              account_type, user_id, asset, opening_units, entry_id)
  FROM ordered
 WHERE ${STRICT_ZERO_OPENING_SQL}
   AND rn = 1
   AND opening_units <> 0;

WITH final_ledger AS (
    SELECT DISTINCT ON (account_type, user_id, asset)
           product_line, account_type, user_id, asset, entry_id, balance_after_units
      FROM product_ledger_scope
     ORDER BY account_type, user_id, asset, entry_id DESC
),
all_keys AS (
    SELECT account_type, user_id, asset FROM final_ledger
    UNION
    SELECT account_type, user_id, asset FROM product_balance_scope
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'product_final_balance_mismatch',
       format('account_type=%s user=%s asset=%s ledger_final=%s actual_balance=%s available=%s locked=%s deficit=%s final_entry_id=%s',
              k.account_type, k.user_id, k.asset,
              COALESCE(f.balance_after_units, 0), COALESCE(b.balance_units, 0),
              COALESCE(b.available_units, 0), COALESCE(b.locked_units, 0), COALESCE(b.deficit_units, 0),
              COALESCE(f.entry_id, 0))
  FROM all_keys k
  LEFT JOIN final_ledger f
    ON f.account_type = k.account_type
   AND f.user_id = k.user_id
   AND f.asset = k.asset
  LEFT JOIN product_balance_scope b
    ON b.account_type = k.account_type
   AND b.user_id = k.user_id
   AND b.asset = k.asset
 WHERE COALESCE(f.balance_after_units, 0) <> COALESCE(b.balance_units, 0);

WITH final_ledger AS (
    SELECT DISTINCT ON (user_id, asset)
           product_line, account_type, user_id, asset, entry_id, balance_after_units
      FROM legacy_ledger_scope
     ORDER BY user_id, asset, entry_id DESC
),
all_keys AS (
    SELECT user_id, asset FROM final_ledger
    UNION
    SELECT user_id, asset FROM legacy_balance_scope
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'legacy_final_balance_mismatch',
       format('account_type=%s user=%s asset=%s ledger_final=%s actual_balance=%s available=%s locked=%s deficit=%s final_entry_id=%s',
              COALESCE(f.account_type, b.account_type), k.user_id, k.asset,
              COALESCE(f.balance_after_units, 0), COALESCE(b.balance_units, 0),
              COALESCE(b.available_units, 0), COALESCE(b.locked_units, 0), COALESCE(b.deficit_units, 0),
              COALESCE(f.entry_id, 0))
  FROM all_keys k
  LEFT JOIN final_ledger f
    ON f.user_id = k.user_id
   AND f.asset = k.asset
  LEFT JOIN legacy_balance_scope b
    ON b.user_id = k.user_id
   AND b.asset = k.asset
 WHERE COALESCE(f.balance_after_units, 0) <> COALESCE(b.balance_units, 0);

INSERT INTO reconcile_violations(area, detail)
SELECT 'unknown_product_reference_type',
       format('entry_id=%s account_type=%s user=%s asset=%s reference=%s:%s reason=%s',
              entry_id, account_type, user_id, asset, reference_type, reference_id, COALESCE(reason, ''))
  FROM product_ledger_scope
 WHERE reference_type NOT IN (
       'PRODUCT_BALANCE_ADJUSTMENT',
       'PRODUCT_TRANSFER',
       'POSITION_MARGIN_ADJUSTMENT',
       'SPOT_TRADE',
       'TRADE_PNL',
       'TRADE_FEE',
       'FUNDING',
       'LIQUIDATION_FEE',
       'DELIVERY_SETTLEMENT',
       'OPTION_EXERCISE',
       'OPTION_PREMIUM'
 );

INSERT INTO reconcile_violations(area, detail)
SELECT 'unknown_legacy_reference_type',
       format('entry_id=%s user=%s asset=%s reference=%s:%s reason=%s',
              entry_id, user_id, asset, reference_type, reference_id, COALESCE(reason, ''))
  FROM legacy_ledger_scope
 WHERE reference_type NOT IN (
       'BALANCE_ADJUSTMENT',
       'POSITION_MARGIN_ADJUSTMENT',
       'TRADE_PNL',
       'TRADE_FEE',
       'FUNDING',
       'LIQUIDATION_FEE',
       'DELIVERY_SETTLEMENT',
       'OPTION_EXERCISE',
       'OPTION_PREMIUM'
 );

INSERT INTO reconcile_violations(area, detail)
SELECT 'admin_product_adjustment_mismatch',
       format('adjustment_id=%s account_type=%s user=%s asset=%s reference_id=%s amount=%s admin_balance_after=%s ledger_amount=%s ledger_balance_after=%s',
              a.adjustment_id, a.account_type, a.user_id, a.asset, a.reference_id, a.amount_units,
              a.balance_after_units, COALESCE(l.amount_units, 0), COALESCE(l.balance_after_units, 0))
  FROM account_admin_balance_adjustments a
  JOIN target_product_lines t
    ON t.account_type = a.account_type
   AND NOT t.uses_legacy
  LEFT JOIN account_product_ledger_entries l
    ON l.reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'
   AND l.reference_id = a.reference_id
   AND l.user_id = a.user_id
   AND l.account_type = a.account_type
   AND l.asset = a.asset
 WHERE a.adjustment_kind = 'PRODUCT'
   AND (l.entry_id IS NULL
        OR l.amount_units <> a.amount_units
        OR l.balance_after_units <> a.balance_after_units);

INSERT INTO reconcile_violations(area, detail)
SELECT 'admin_legacy_adjustment_mismatch',
       format('adjustment_id=%s adjustment_kind=%s account_type=%s user=%s asset=%s reference_id=%s amount=%s admin_balance_after=%s ledger_amount=%s ledger_balance_after=%s',
              a.adjustment_id, a.adjustment_kind, COALESCE(a.account_type, 'BASIC'), a.user_id, a.asset,
              a.reference_id, a.amount_units, a.balance_after_units,
              COALESCE(l.amount_units, 0), COALESCE(l.balance_after_units, 0))
  FROM account_admin_balance_adjustments a
  LEFT JOIN target_product_lines t
    ON t.account_type = a.account_type
   AND t.uses_legacy
  LEFT JOIN account_ledger_entries l
    ON l.reference_type = 'BALANCE_ADJUSTMENT'
   AND l.reference_id = CASE
          WHEN a.adjustment_kind = 'PRODUCT' THEN a.account_type || ':' || a.reference_id
          ELSE a.reference_id
       END
   AND l.user_id = a.user_id
   AND l.asset = a.asset
 WHERE ((a.adjustment_kind = 'BASIC' AND EXISTS (SELECT 1 FROM target_product_lines WHERE uses_legacy))
        OR (a.adjustment_kind = 'PRODUCT' AND t.account_type IS NOT NULL))
   AND (l.entry_id IS NULL
        OR l.amount_units <> a.amount_units
        OR l.balance_after_units <> a.balance_after_units);

WITH transfers AS (
    SELECT pt.transfer_id,
           pt.user_id,
           pt.source_account_type,
           pt.target_account_type,
           pt.asset,
           pt.amount_units,
           pt.reference_id,
           pt.status
      FROM account_product_transfers pt
     WHERE pt.source_account_type IN (SELECT account_type FROM target_product_lines WHERE NOT uses_legacy)
        OR pt.target_account_type IN (SELECT account_type FROM target_product_lines WHERE NOT uses_legacy)
),
ledger_pairs AS (
    SELECT tr.transfer_id,
           tr.user_id,
           tr.source_account_type,
           tr.target_account_type,
           tr.asset,
           tr.amount_units,
           tr.reference_id,
           out_l.amount_units AS out_amount,
           in_l.amount_units AS in_amount
      FROM transfers tr
      LEFT JOIN account_product_ledger_entries out_l
        ON out_l.reference_type = 'PRODUCT_TRANSFER'
       AND out_l.reference_id = tr.reference_id || ':OUT'
       AND out_l.user_id = tr.user_id
       AND out_l.account_type = tr.source_account_type
       AND out_l.asset = tr.asset
      LEFT JOIN account_product_ledger_entries in_l
        ON in_l.reference_type = 'PRODUCT_TRANSFER'
       AND in_l.reference_id = tr.reference_id || ':IN'
       AND in_l.user_id = tr.user_id
       AND in_l.account_type = tr.target_account_type
       AND in_l.asset = tr.asset
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'product_transfer_ledger_mismatch',
       format('transfer_id=%s user=%s source=%s target=%s asset=%s amount=%s out_amount=%s in_amount=%s reference=%s',
              transfer_id, user_id, source_account_type, target_account_type, asset, amount_units,
              COALESCE(out_amount, 0), COALESCE(in_amount, 0), reference_id)
  FROM ledger_pairs
 WHERE COALESCE(out_amount, 0) <> -amount_units
    OR COALESCE(in_amount, 0) <> amount_units;

WITH instrument_products AS (
    SELECT DISTINCT symbol,
           CASE contract_type
               WHEN 'VANILLA_OPTION' THEN 'OPTION'
               ELSE contract_type
           END AS product_line
      FROM instruments
),
funding_payment_scope AS (
    SELECT ip.product_line,
           t.account_type,
           fp.settlement_id,
           fp.user_id,
           fp.symbol,
           fp.margin_mode,
           fp.position_side,
           fp.asset,
           fp.amount_units,
           fp.command_id AS reference_id
      FROM funding_payments fp
      JOIN instrument_products ip
        ON ip.symbol = fp.symbol
      JOIN target_product_lines t
        ON t.product_line = ip.product_line
       AND t.funding_product
),
funding_ledger_scope AS (
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM product_ledger_scope
     WHERE reference_type = 'FUNDING'
    UNION ALL
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM legacy_ledger_scope
     WHERE reference_type = 'FUNDING'
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'funding_payment_ledger_mismatch',
       format('product_line=%s account_type=%s settlement=%s user=%s symbol=%s asset=%s reference=%s payment_amount=%s ledger_amount=%s',
              p.product_line, p.account_type, p.settlement_id, p.user_id, p.symbol, p.asset, p.reference_id,
              p.amount_units, COALESCE(l.amount_units, 0))
  FROM funding_payment_scope p
  LEFT JOIN funding_ledger_scope l
    ON l.product_line = p.product_line
   AND l.account_type = p.account_type
   AND l.user_id = p.user_id
   AND l.asset = p.asset
   AND l.reference_id = p.reference_id
 WHERE l.reference_id IS NULL
    OR l.amount_units <> p.amount_units;

WITH instrument_products AS (
    SELECT DISTINCT symbol,
           CASE contract_type
               WHEN 'VANILLA_OPTION' THEN 'OPTION'
               ELSE contract_type
           END AS product_line
      FROM instruments
),
funding_payment_scope AS (
    SELECT ip.product_line,
           fp.settlement_id,
           fp.symbol,
           fp.asset,
           fp.amount_units
      FROM funding_payments fp
      JOIN instrument_products ip
        ON ip.symbol = fp.symbol
      JOIN target_product_lines t
        ON t.product_line = ip.product_line
       AND t.funding_product
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'funding_settlement_not_conserved',
       format('product_line=%s settlement=%s symbol=%s asset=%s net_payment_amount=%s',
              product_line, settlement_id, symbol, asset, SUM(amount_units))
  FROM funding_payment_scope
 GROUP BY product_line, settlement_id, symbol, asset
HAVING SUM(amount_units) <> 0;

WITH funding_ledger_scope AS (
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM product_ledger_scope
     WHERE reference_type = 'FUNDING'
    UNION ALL
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM legacy_ledger_scope
     WHERE reference_type = 'FUNDING'
),
instrument_products AS (
    SELECT DISTINCT symbol,
           CASE contract_type
               WHEN 'VANILLA_OPTION' THEN 'OPTION'
               ELSE contract_type
           END AS product_line
      FROM instruments
),
funding_payment_scope AS (
    SELECT ip.product_line,
           fp.user_id,
           fp.asset,
           fp.command_id AS reference_id
      FROM funding_payments fp
      JOIN instrument_products ip
        ON ip.symbol = fp.symbol
      JOIN target_product_lines t
        ON t.product_line = ip.product_line
       AND t.funding_product
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'funding_ledger_without_payment',
       format('product_line=%s account_type=%s user=%s asset=%s reference=%s amount=%s',
              l.product_line, l.account_type, l.user_id, l.asset, l.reference_id, l.amount_units)
  FROM funding_ledger_scope l
  LEFT JOIN funding_payment_scope p
    ON p.product_line = l.product_line
   AND p.user_id = l.user_id
   AND p.asset = l.asset
   AND p.reference_id = l.reference_id
 WHERE p.reference_id IS NULL;

WITH spot_non_fee AS (
    SELECT split_part(reference_id, ':', 1) AS trade_id,
           asset,
           SUM(amount_units) AS net_amount
      FROM product_ledger_scope
     WHERE account_type = 'SPOT'
       AND reference_type = 'SPOT_TRADE'
       AND COALESCE(reason, '') NOT LIKE '%FEE%'
     GROUP BY split_part(reference_id, ':', 1), asset
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'spot_principal_not_conserved',
       format('trade_id=%s asset=%s net_principal_amount=%s', trade_id, asset, net_amount)
  FROM spot_non_fee
 WHERE net_amount <> 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'spot_ledger_reference_malformed',
       format('entry_id=%s user=%s asset=%s reference_id=%s reason=%s',
              entry_id, user_id, asset, reference_id, COALESCE(reason, ''))
  FROM product_ledger_scope
 WHERE account_type = 'SPOT'
   AND reference_type = 'SPOT_TRADE'
   AND reference_id !~ '^[0-9]+:[0-9]+:[A-Z_]+$';

WITH option_premium AS (
    SELECT account_type,
           asset,
           split_part(reference_id, ':', 1) AS trade_id,
           SUM(amount_units) AS net_amount
      FROM product_ledger_scope
     WHERE reference_type = 'OPTION_PREMIUM'
     GROUP BY account_type, asset, split_part(reference_id, ':', 1)
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'option_premium_not_conserved',
       format('account_type=%s trade_id=%s asset=%s net_premium_amount=%s',
              account_type, trade_id, asset, net_amount)
  FROM option_premium
 WHERE net_amount <> 0;

WITH derivative_cash AS (
    SELECT product_line,
           account_type,
           asset,
           SUM(amount_units) AS net_amount
      FROM product_ledger_scope
     WHERE reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM', 'DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')
     GROUP BY product_line, account_type, asset
    UNION ALL
    SELECT product_line,
           account_type,
           asset,
           SUM(amount_units) AS net_amount
      FROM legacy_ledger_scope
     WHERE reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM', 'DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')
     GROUP BY product_line, account_type, asset
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'derivative_cash_not_conserved',
       format('product_line=%s account_type=%s asset=%s net_trade_lifecycle_amount=%s',
              product_line, account_type, asset, SUM(net_amount))
  FROM derivative_cash
 GROUP BY product_line, account_type, asset
HAVING SUM(net_amount) <> 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'lifecycle_reference_malformed',
       format('entry_id=%s product_line=%s account_type=%s user=%s asset=%s reference=%s:%s',
              entry_id, product_line, account_type, user_id, asset, reference_type, reference_id)
  FROM product_ledger_scope
 WHERE reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')
   AND reference_id !~ '^(DELIVERY_SETTLEMENT|OPTION_EXERCISE):[A-Z0-9][A-Z0-9_-]{1,63}:[0-9]+:[0-9]+:(CROSS|ISOLATED):(NET|LONG|SHORT)$';

INSERT INTO reconcile_violations(area, detail)
SELECT 'lifecycle_reference_malformed',
       format('entry_id=%s product_line=%s account_type=%s user=%s asset=%s reference=%s:%s',
              entry_id, product_line, account_type, user_id, asset, reference_type, reference_id)
  FROM legacy_ledger_scope
 WHERE reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')
   AND reference_id !~ '^(DELIVERY_SETTLEMENT|OPTION_EXERCISE):[A-Z0-9][A-Z0-9_-]{1,63}:[0-9]+:[0-9]+:(CROSS|ISOLATED):(NET|LONG|SHORT)$';

WITH product_liq AS (
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM product_ledger_scope
     WHERE reference_type = 'LIQUIDATION_FEE'
    UNION ALL
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM legacy_ledger_scope
     WHERE reference_type = 'LIQUIDATION_FEE'
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'liquidation_fee_not_debit',
       format('product_line=%s account_type=%s user=%s asset=%s reference=%s amount=%s',
              product_line, account_type, user_id, asset, reference_id, amount_units)
  FROM product_liq
 WHERE amount_units >= 0;

WITH product_liq AS (
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM product_ledger_scope
     WHERE reference_type = 'LIQUIDATION_FEE'
    UNION ALL
    SELECT product_line, account_type, user_id, asset, reference_id, amount_units
      FROM legacy_ledger_scope
     WHERE reference_type = 'LIQUIDATION_FEE'
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'liquidation_fee_insurance_mismatch',
       format('product_line=%s account_type=%s user=%s asset=%s reference=%s user_debit=%s insurance_credit=%s',
              l.product_line, l.account_type, l.user_id, l.asset, l.reference_id, l.amount_units,
              COALESCE(i.amount_units, 0))
  FROM product_liq l
  LEFT JOIN insurance_fund_ledger i
    ON i.reference_type = 'LIQUIDATION_FEE'
   AND i.reference_id = l.reference_id
   AND i.account_type = l.account_type
   AND i.asset = l.asset
 WHERE i.entry_id IS NULL
    OR i.amount_units <> -l.amount_units;

INSERT INTO reconcile_violations(area, detail)
SELECT 'legacy_fee_metadata_missing',
       format('entry_id=%s user=%s asset=%s reference=%s:%s trade_id=%s order_id=%s symbol=%s fee_rate=%s',
              entry_id, user_id, asset, reference_type, reference_id,
              COALESCE(trade_id, 0), COALESCE(order_id, 0), COALESCE(symbol, ''), COALESCE(fee_rate_ppm, 0))
  FROM legacy_ledger_scope
 WHERE reference_type IN ('TRADE_FEE', 'LIQUIDATION_FEE')
   AND (trade_id IS NULL OR order_id IS NULL OR symbol IS NULL OR fee_rate_ppm IS NULL);

INSERT INTO reconcile_violations(area, detail)
SELECT 'product_fee_reference_malformed',
       format('entry_id=%s account_type=%s user=%s asset=%s reference=%s:%s',
              entry_id, account_type, user_id, asset, reference_type, reference_id)
  FROM product_ledger_scope
 WHERE reference_type IN ('TRADE_FEE', 'LIQUIDATION_FEE')
   AND reference_id !~ '^[0-9]+:[0-9]+$';

WITH product_fee_refs AS (
    SELECT l.product_line,
           l.account_type,
           l.entry_id,
           l.user_id,
           l.asset,
           l.reference_type,
           l.reference_id,
           split_part(l.reference_id, ':', 1)::bigint AS trade_id,
           split_part(l.reference_id, ':', 2)::bigint AS order_id
      FROM product_ledger_scope l
     WHERE l.reference_type IN ('TRADE_FEE', 'LIQUIDATION_FEE')
       AND l.reference_id ~ '^[0-9]+:[0-9]+$'
),
matched_refs AS (
    SELECT f.entry_id,
           mt.trade_id
      FROM product_fee_refs f
      JOIN match_trade_order_refs mt
        ON mt.product_line = f.product_line
       AND mt.trade_id = f.trade_id
       AND mt.order_id = f.order_id
       AND mt.user_id = f.user_id
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'product_fee_trade_reference_missing',
       format('entry_id=%s product_line=%s account_type=%s user=%s asset=%s reference=%s:%s',
              f.entry_id, f.product_line, f.account_type, f.user_id, f.asset, f.reference_type, f.reference_id)
  FROM product_fee_refs f
  LEFT JOIN matched_refs m
    ON m.entry_id = f.entry_id
 WHERE m.entry_id IS NULL;

WITH legacy_fee_refs AS (
    SELECT l.product_line,
           l.account_type,
           l.entry_id,
           l.user_id,
           l.asset,
           l.reference_type,
           l.reference_id,
           l.trade_id,
           l.order_id
      FROM legacy_ledger_scope l
     WHERE l.reference_type IN ('TRADE_FEE', 'LIQUIDATION_FEE')
       AND l.trade_id IS NOT NULL
       AND l.order_id IS NOT NULL
),
matched_refs AS (
    SELECT f.entry_id,
           mt.trade_id
      FROM legacy_fee_refs f
      JOIN match_trade_order_refs mt
        ON mt.product_line = f.product_line
       AND mt.trade_id = f.trade_id
       AND mt.order_id = f.order_id
       AND mt.user_id = f.user_id
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'legacy_fee_trade_reference_missing',
       format('entry_id=%s product_line=%s account_type=%s user=%s asset=%s reference=%s:%s trade_id=%s order_id=%s',
              f.entry_id, f.product_line, f.account_type, f.user_id, f.asset, f.reference_type,
              f.reference_id, f.trade_id, f.order_id)
  FROM legacy_fee_refs f
  LEFT JOIN matched_refs m
    ON m.entry_id = f.entry_id
 WHERE m.entry_id IS NULL;

INSERT INTO reconcile_violations(area, detail)
SELECT 'negative_position_margin',
       format('product_line=%s user=%s symbol=%s asset=%s margin_mode=%s position_side=%s margin=%s',
              m.product_line, m.user_id, m.symbol, m.asset, m.margin_mode, m.position_side, m.margin_units)
  FROM account_position_margins m
  JOIN target_product_lines t
    ON t.product_line = m.product_line
 WHERE m.margin_units < 0;

INSERT INTO reconcile_violations(area, detail)
SELECT 'position_margin_without_position',
       format('product_line=%s user=%s symbol=%s asset=%s margin_mode=%s position_side=%s margin=%s',
              m.product_line, m.user_id, m.symbol, m.asset, m.margin_mode, m.position_side, m.margin_units)
  FROM account_position_margins m
  JOIN target_product_lines t
    ON t.product_line = m.product_line
  LEFT JOIN account_positions p
    ON p.product_line = m.product_line
   AND p.user_id = m.user_id
   AND p.symbol = m.symbol
   AND p.margin_mode = m.margin_mode
   AND p.position_side = m.position_side
   AND p.signed_quantity_steps <> 0
 WHERE m.margin_units > 0
   AND p.user_id IS NULL;

WITH margin_by_balance AS (
    SELECT CASE WHEN t.uses_legacy THEN 'LEGACY' ELSE 'PRODUCT' END AS ledger_scope,
           t.product_line,
           t.account_type,
           m.user_id,
           m.asset,
           SUM(m.margin_units) AS margin_units
      FROM account_position_margins m
      JOIN target_product_lines t
        ON t.product_line = m.product_line
     GROUP BY CASE WHEN t.uses_legacy THEN 'LEGACY' ELSE 'PRODUCT' END,
              t.product_line, t.account_type, m.user_id, m.asset
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'locked_less_than_position_margin',
       format('product_line=%s account_type=%s user=%s asset=%s locked=%s position_margin=%s',
              m.product_line, m.account_type, m.user_id, m.asset, COALESCE(b.locked_units, 0), m.margin_units)
  FROM margin_by_balance m
  LEFT JOIN balance_scope b
    ON b.ledger_scope = m.ledger_scope
   AND b.account_type = m.account_type
   AND b.user_id = m.user_id
   AND b.asset = m.asset
 WHERE COALESCE(b.locked_units, 0) < m.margin_units;

INSERT INTO reconcile_violations(area, detail)
SELECT 'spot_reservation_invalid',
       format('user=%s order=%s symbol=%s asset=%s reserved=%s settled=%s released=%s status=%s',
              r.user_id, r.order_id, r.symbol, r.asset, r.reserved_units, r.settled_units, r.released_units, r.status)
  FROM account_spot_order_reservations r
 WHERE EXISTS (SELECT 1 FROM target_product_lines WHERE product_line = 'SPOT')
   AND (r.reserved_units <= 0
        OR r.settled_units < 0
        OR r.released_units < 0
        OR r.settled_units + r.released_units > r.reserved_units);

WITH spot_reserved AS (
    SELECT user_id,
           asset,
           SUM(reserved_units - settled_units - released_units) AS expected_locked_units
      FROM account_spot_order_reservations
     WHERE status NOT IN ('SETTLED', 'RELEASED')
     GROUP BY user_id, asset
),
spot_balance_keys AS (
    SELECT user_id, asset FROM product_balance_scope WHERE account_type = 'SPOT'
    UNION
    SELECT user_id, asset FROM spot_reserved
)
INSERT INTO reconcile_violations(area, detail)
SELECT 'spot_locked_reservation_mismatch',
       format('user=%s asset=%s locked_balance=%s expected_reservation_locked=%s',
              k.user_id, k.asset, COALESCE(b.locked_units, 0), COALESCE(r.expected_locked_units, 0))
  FROM spot_balance_keys k
  LEFT JOIN product_balance_scope b
    ON b.account_type = 'SPOT'
   AND b.user_id = k.user_id
   AND b.asset = k.asset
  LEFT JOIN spot_reserved r
    ON r.user_id = k.user_id
   AND r.asset = k.asset
 WHERE EXISTS (SELECT 1 FROM target_product_lines WHERE product_line = 'SPOT')
   AND COALESCE(b.locked_units, 0) <> COALESCE(r.expected_locked_units, 0);

\\echo '[funds-reconcile] Scope'
SELECT product_line,
       account_type,
       contract_type,
       CASE WHEN uses_legacy THEN 'account_ledger_entries' ELSE 'account_product_ledger_entries' END AS ledger_table
  FROM target_product_lines
 ORDER BY product_line;

\\echo '[funds-reconcile] Per account conservation report (limited by MAX_REPORT_ROWS)'
WITH ordered AS (
    SELECT l.*,
           FIRST_VALUE(balance_after_units - amount_units) OVER (
               PARTITION BY ledger_scope, account_type, user_id, asset
               ORDER BY entry_id
           ) AS opening_units,
           ROW_NUMBER() OVER (
               PARTITION BY ledger_scope, account_type, user_id, asset
               ORDER BY entry_id DESC
           ) AS reverse_rn
      FROM ledger_scope l
),
ledger_totals AS (
    SELECT ledger_scope,
           product_line,
           account_type,
           user_id,
           asset,
           COALESCE(MAX(opening_units), 0) AS opening_units,
           SUM(amount_units) FILTER (
               WHERE reference_type IN ('PRODUCT_BALANCE_ADJUSTMENT', 'BALANCE_ADJUSTMENT')
           ) AS adjustment_units,
           SUM(amount_units) FILTER (
               WHERE reference_type = 'SPOT_TRADE'
                 AND COALESCE(reason, '') NOT LIKE '%FEE%'
           ) AS spot_trade_units,
           SUM(amount_units) FILTER (
               WHERE reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
           ) AS derivative_trade_units,
           SUM(amount_units) FILTER (
               WHERE reference_type = 'SPOT_TRADE'
                 AND COALESCE(reason, '') LIKE '%FEE%'
           ) AS spot_fee_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'TRADE_FEE') AS trade_fee_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'FUNDING') AS funding_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'LIQUIDATION_FEE') AS liquidation_fee_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'DELIVERY_SETTLEMENT') AS delivery_settlement_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'OPTION_EXERCISE') AS option_exercise_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'PRODUCT_TRANSFER') AS transfer_units,
           SUM(amount_units) FILTER (WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT') AS margin_adjustment_units,
           SUM(amount_units) AS ledger_delta_units,
           MAX(balance_after_units) FILTER (WHERE reverse_rn = 1) AS final_ledger_units
      FROM ordered
     GROUP BY ledger_scope, product_line, account_type, user_id, asset
),
keys AS (
    SELECT ledger_scope, product_line, account_type, user_id, asset FROM ledger_totals
    UNION
    SELECT ledger_scope, product_line, account_type, user_id, asset FROM balance_scope
)
SELECT k.ledger_scope,
       k.product_line,
       k.account_type,
       k.user_id,
       k.asset,
       COALESCE(t.opening_units, 0) AS opening_units,
       COALESCE(t.adjustment_units, 0) AS adjustment_units,
       COALESCE(t.spot_trade_units, 0) + COALESCE(t.derivative_trade_units, 0) AS trade_units,
       COALESCE(t.spot_fee_units, 0) + COALESCE(t.trade_fee_units, 0) AS fee_units,
       COALESCE(t.funding_units, 0) AS funding_units,
       COALESCE(t.liquidation_fee_units, 0) AS liquidation_fee_units,
       COALESCE(t.delivery_settlement_units, 0) AS delivery_settlement_units,
       COALESCE(t.option_exercise_units, 0) AS option_exercise_units,
       COALESCE(t.transfer_units, 0) AS transfer_units,
       COALESCE(t.margin_adjustment_units, 0) AS margin_adjustment_units,
       COALESCE(t.ledger_delta_units, 0) AS ledger_delta_units,
       COALESCE(t.final_ledger_units, 0) AS final_ledger_units,
       COALESCE(b.available_units, 0) AS final_available_units,
       COALESCE(b.locked_units, 0) AS final_locked_units,
       COALESCE(b.deficit_units, 0) AS final_deficit_units,
       COALESCE(b.balance_units, 0) AS final_balance_units
  FROM keys k
  LEFT JOIN ledger_totals t
    ON t.ledger_scope = k.ledger_scope
   AND t.account_type = k.account_type
   AND t.user_id = k.user_id
   AND t.asset = k.asset
  LEFT JOIN balance_scope b
    ON b.ledger_scope = k.ledger_scope
   AND b.account_type = k.account_type
   AND b.user_id = k.user_id
   AND b.asset = k.asset
 ORDER BY k.product_line, k.account_type, k.user_id, k.asset
 LIMIT ${MAX_REPORT_ROWS};

\\echo '[funds-reconcile] Product-line totals'
SELECT product_line,
       account_type,
       asset,
       COALESCE(SUM(amount_units) FILTER (
           WHERE reference_type IN ('PRODUCT_BALANCE_ADJUSTMENT', 'BALANCE_ADJUSTMENT')
       ), 0) AS adjustment_units,
       COALESCE(SUM(amount_units) FILTER (
           WHERE reference_type = 'SPOT_TRADE'
             AND COALESCE(reason, '') NOT LIKE '%FEE%'
       ), 0) AS spot_principal_units,
       COALESCE(SUM(amount_units) FILTER (
           WHERE reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
       ), 0) AS derivative_trade_units,
       COALESCE(SUM(amount_units) FILTER (
           WHERE reference_type = 'SPOT_TRADE'
             AND COALESCE(reason, '') LIKE '%FEE%'
       ), 0) + COALESCE(SUM(amount_units) FILTER (WHERE reference_type = 'TRADE_FEE'), 0) AS fee_units,
       COALESCE(SUM(amount_units) FILTER (WHERE reference_type = 'FUNDING'), 0) AS funding_units,
       COALESCE(SUM(amount_units) FILTER (WHERE reference_type = 'LIQUIDATION_FEE'), 0) AS liquidation_fee_units,
       COALESCE(SUM(amount_units) FILTER (WHERE reference_type = 'DELIVERY_SETTLEMENT'), 0) AS delivery_settlement_units,
       COALESCE(SUM(amount_units) FILTER (WHERE reference_type = 'OPTION_EXERCISE'), 0) AS option_exercise_units,
       SUM(amount_units) AS total_ledger_delta_units
  FROM ledger_scope
 GROUP BY product_line, account_type, asset
 ORDER BY product_line, account_type, asset;

\\echo '[funds-reconcile] Violations'
SELECT area, detail
  FROM reconcile_violations
 ORDER BY area, detail
 LIMIT ${MAX_REPORT_ROWS};

DO \$\$
DECLARE
    violation_count bigint;
BEGIN
    SELECT count(*) INTO violation_count FROM reconcile_violations;
    IF violation_count <> 0 THEN
        RAISE EXCEPTION 'funds reconciliation failed: % violation(s)', violation_count;
    END IF;
END
\$\$;
SQL

echo "[funds-reconcile] OK"
