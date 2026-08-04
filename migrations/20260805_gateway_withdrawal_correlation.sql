BEGIN;

DO $$
BEGIN
    IF to_regclass('public.gateway_wallet_withdrawals') IS NULL THEN
        RETURN;
    END IF;

    EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
             ADD COLUMN IF NOT EXISTS external_reference TEXT';
    EXECUTE $sql$
        UPDATE gateway_wallet_withdrawals
           SET external_reference = 'custody-wallet-withdrawal:legacy:' || withdrawal_id::text
         WHERE external_reference IS NULL
    $sql$;

    IF EXISTS (
        SELECT 1
          FROM gateway_wallet_withdrawals
         GROUP BY external_reference
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'gateway_wallet_withdrawals contains duplicate external_reference values';
    END IF;

    EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
             ALTER COLUMN external_reference SET NOT NULL';
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'gateway_wallet_withdrawal_external_reference_uq'
           AND conrelid = 'public.gateway_wallet_withdrawals'::regclass
    ) THEN
        EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
                 ADD CONSTRAINT gateway_wallet_withdrawal_external_reference_uq
                 UNIQUE (external_reference)';
    END IF;
END
$$;

COMMIT;
