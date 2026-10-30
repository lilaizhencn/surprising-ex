-- Apply with Gateway stopped. Historical withdrawals never charged a platform fee.
BEGIN;
LOCK TABLE gateway_wallet_withdrawals IN ACCESS EXCLUSIVE MODE;
ALTER TABLE gateway_wallet_withdrawals
    ALTER COLUMN custody_address_id DROP NOT NULL,
    ADD COLUMN funding_source TEXT NOT NULL DEFAULT 'ADDRESS',
    ADD COLUMN reconcile_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN fee_amount TEXT,
    ADD COLUMN fee_units BIGINT,
    ADD COLUMN received_amount TEXT,
    ADD COLUMN received_units BIGINT,
    ADD COLUMN network_id INTEGER,
    ADD COLUMN network_revision BIGINT;
UPDATE gateway_wallet_withdrawals SET fee_amount='0',fee_units=0,received_amount=amount,
    received_units=amount_units,network_id=0,network_revision=0;
ALTER TABLE gateway_wallet_withdrawals
    ALTER COLUMN funding_source DROP DEFAULT,
    ADD CONSTRAINT gateway_wallet_withdrawal_source_check CHECK (funding_source IN ('ADDRESS', 'TENANT_POOL') AND ((funding_source = 'ADDRESS') = (custody_address_id IS NOT NULL))),
    ALTER COLUMN fee_amount SET NOT NULL, ALTER COLUMN fee_units SET NOT NULL,
    ALTER COLUMN received_amount SET NOT NULL, ALTER COLUMN received_units SET NOT NULL,
    ALTER COLUMN network_id SET NOT NULL, ALTER COLUMN network_revision SET NOT NULL,
    ADD CONSTRAINT gateway_wallet_withdrawal_fee_check CHECK (fee_units >= 0 AND received_units > 0 AND amount_units = fee_units + received_units);
CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_fees (
    withdrawal_id UUID PRIMARY KEY REFERENCES gateway_wallet_withdrawals(withdrawal_id),
    user_id BIGINT NOT NULL,
    asset_symbol TEXT NOT NULL,
    fee_units BIGINT NOT NULL CHECK (fee_units >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE gateway_wallet_withdrawal_fees IS '提现链上确认后唯一确认的平台手续费收入；与实际链费账务分开';
COMMENT ON COLUMN gateway_wallet_withdrawals.funding_source IS '本金来源：租户资金池 TENANT_POOL 或历史地址出款 ADDRESS；独立于提现用户';
COMMENT ON COLUMN gateway_wallet_withdrawals.fee_amount IS '原报价平台手续费十进制金额，从用户总扣款内扣';
COMMENT ON COLUMN gateway_wallet_withdrawals.fee_units IS '原报价平台手续费的 Core 最小单位数量';
COMMENT ON COLUMN gateway_wallet_withdrawals.received_amount IS '原报价链上收款本金十进制金额，等于总扣款减平台费';
COMMENT ON COLUMN gateway_wallet_withdrawals.received_units IS '原报价链上收款本金的 Core 最小单位数量';
COMMENT ON COLUMN gateway_wallet_withdrawals.network_id IS '原报价采用的币种提现网络配置主键；历史未报价单为零';
COMMENT ON COLUMN gateway_wallet_withdrawals.network_revision IS '原报价确认的网络配置版本，恢复时不可重新计费';
COMMENT ON COLUMN gateway_wallet_withdrawals.reconcile_after IS '恢复任务下次可领取时间；租约到期不授权退款';
COMMENT ON COLUMN gateway_wallet_withdrawal_fees.withdrawal_id IS '已确认提现主键，同时作为平台手续费收入唯一幂等键';
COMMENT ON COLUMN gateway_wallet_withdrawal_fees.user_id IS '承担该笔平台手续费的交易所用户主键';
COMMENT ON COLUMN gateway_wallet_withdrawal_fees.asset_symbol IS '平台手续费计价资产的 Core 会计代码';
COMMENT ON COLUMN gateway_wallet_withdrawal_fees.fee_units IS '确认的平台手续费收入，使用该资产 Core 最小单位';
COMMENT ON COLUMN gateway_wallet_withdrawal_fees.created_at IS '该笔平台手续费收入首次确认时间';

CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_fees_immutable_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'gateway wallet withdrawal fees are immutable';
END;
$$;
CREATE TRIGGER gateway_wallet_withdrawal_fees_immutable_trigger
BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_fees
FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_fees_immutable_guard();


CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_status_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('PROCESSING', 'REJECTED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('DEBIT_UNKNOWN', 'DEBITED', 'REJECTED'))
        OR (OLD.status = 'DEBIT_UNKNOWN' AND NEW.status IN ('DEBITED','REJECTED'))
        OR (OLD.status = 'DEBITED' AND NEW.status IN ('SUBMITTED', 'BROADCAST_UNKNOWN', 'COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
        OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
        OR (OLD.status = 'BROADCAST_UNKNOWN' AND NEW.status IN ('SUBMITTED', 'COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
        OR (OLD.status = 'FAILED_PENDING' AND NEW.status IN ('COMPLETED', 'REFUND_PENDING', 'REFUNDED'))
        OR (OLD.status = 'REFUND_PENDING' AND NEW.status = 'REFUNDED')
    ) THEN
        RAISE EXCEPTION 'illegal gateway wallet withdrawal status transition: % -> %', OLD.status, NEW.status;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM gateway_wallet_withdrawal_events
         WHERE withdrawal_id = NEW.withdrawal_id
           AND from_status IS NOT DISTINCT FROM OLD.status
           AND to_status IS NOT DISTINCT FROM NEW.status
           AND created_at >= transaction_timestamp()
    ) THEN
        RAISE EXCEPTION 'gateway wallet withdrawal status transition is missing an immutable audit event';
    END IF;
    RETURN NEW;
END;
$$;
COMMIT;
