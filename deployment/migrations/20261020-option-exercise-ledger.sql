-- 仅纠正旧 realtime 投影的期权流水分类，不改变资金整数、余额、流水身份及检查点。
-- 停止相关 realtime exporter 后执行；冲突时整笔回滚，先对账，不删除重复历史事实。
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
LOCK TABLE account_product_ledger_entries IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM account_product_ledger_entries old
        JOIN account_product_ledger_entries corrected
          ON corrected.reference_type = 'OPTION_EXERCISE'
         AND corrected.reference_id = old.reference_id
         AND corrected.user_id = old.user_id
         AND corrected.account_type = old.account_type
         AND corrected.asset = old.asset
        WHERE old.account_type = 'OPTION'
          AND old.reference_type = 'DELIVERY_SETTLEMENT'
          AND old.reference_id LIKE 'OPTION:%'
    ) THEN
        RAISE EXCEPTION 'conflicting option exercise ledger identities; reconcile before migration';
    END IF;
    UPDATE account_product_ledger_entries
       SET reference_type = 'OPTION_EXERCISE', reason = '期权行权'
     WHERE account_type = 'OPTION'
       AND reference_type = 'DELIVERY_SETTLEMENT'
       AND reference_id LIKE 'OPTION:%';
END;
$$;
COMMIT;
