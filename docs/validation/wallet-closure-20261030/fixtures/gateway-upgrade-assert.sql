DO $$ BEGIN
 IF current_database() !~ '^surprising_exchange_test_wallet_upgrade_[0-9]{8}$' THEN RAISE EXCEPTION 'isolated upgrade database required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM gateway_wallet_withdrawals WHERE withdrawal_id='bbbbbbbb-0000-0000-0000-000000000043' AND funding_source='ADDRESS' AND custody_address_id='aaaaaaaa-0000-0000-0000-000000000043' AND amount='25' AND amount_units=25000000 AND fee_amount='0' AND fee_units=0 AND received_amount='25' AND received_units=25000000 AND network_id=0 AND network_revision=0 AND status='BROADCAST_UNKNOWN' AND spot_debit_reference='upgrade-withdrawal') THEN RAISE EXCEPTION 'historical withdrawal facts changed'; END IF;
 IF EXISTS(SELECT 1 FROM gateway_wallet_withdrawal_fees) THEN RAISE EXCEPTION 'upgrade invented platform income'; END IF;
END $$;
