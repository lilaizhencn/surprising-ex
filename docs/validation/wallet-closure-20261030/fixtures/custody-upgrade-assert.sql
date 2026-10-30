DO $$ BEGIN
 IF current_database() !~ '^surprising_wallet_test_upgrade_[0-9]{8}$' THEN RAISE EXCEPTION 'isolated upgrade database required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM custody_withdrawal WHERE id='cccccccc-0000-0000-0000-000000000043' AND subject='user:43' AND funding_source='ADDRESS' AND status='SENT' AND amount=1) THEN RAISE EXCEPTION 'historical beneficiary or principal changed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM withdrawal_order WHERE tenant_id='bbbbbbbb-0000-0000-0000-000000000043' AND order_no='upgrade-withdrawal' AND status='SENT' AND tx_hash='0x'||repeat('ab',32) AND NOT funds_released AND NOT durable_submission) THEN RAISE EXCEPTION 'upgrade invented a failed or safely repeatable payment'; END IF;
 IF NOT EXISTS(SELECT 1 FROM ledger_balance WHERE account_id='upgrade-principal' AND available_balance=1 AND locked_balance=1 AND total_balance=2) THEN RAISE EXCEPTION 'upgrade moved historical funds'; END IF;
 IF NOT EXISTS(SELECT 1 FROM custody_idempotency_key WHERE tenant_id='bbbbbbbb-0000-0000-0000-000000000043' AND idempotency_key='upgrade-withdrawal' AND expires_at IS NULL AND response_status=202 AND request_hash=repeat('a',64)) THEN RAISE EXCEPTION 'permanent acceptance was not preserved'; END IF;
END $$;
