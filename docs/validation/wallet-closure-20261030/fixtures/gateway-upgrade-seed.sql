DO $$ BEGIN
 IF current_database() !~ '^surprising_exchange_test_wallet_upgrade_[0-9]{8}$' THEN RAISE EXCEPTION 'isolated upgrade database required'; END IF;
END $$;
INSERT INTO gateway_users(user_id,username,password_hash) VALUES (900000043,'wallet_upgrade_fixture','test-only-non-login-hash');
INSERT INTO gateway_wallet_withdrawals(withdrawal_id,user_id,idempotency_key,request_sha256,chain,asset_symbol,custody_address_id,to_address,amount,amount_units,usdt_value,external_reference,spot_debit_reference,request_payload,status)
VALUES ('bbbbbbbb-0000-0000-0000-000000000043',900000043,'upgrade-withdrawal',repeat('a',64),'ETH','USDT','aaaaaaaa-0000-0000-0000-000000000043','synthetic-recipient','25',25000000,25,'upgrade-withdrawal','upgrade-withdrawal','{}','BROADCAST_UNKNOWN');
