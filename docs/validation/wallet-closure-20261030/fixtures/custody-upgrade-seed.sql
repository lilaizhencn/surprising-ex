DO $$ DECLARE
 tenant uuid := 'bbbbbbbb-0000-0000-0000-000000000043';
 address_id uuid := 'aaaaaaaa-0000-0000-0000-000000000043';
 chain_address bigint; order_id bigint;
 source text := '0x'||repeat('12',20);
BEGIN
 IF current_database() !~ '^surprising_wallet_test_upgrade_[0-9]{8}$' THEN RAISE EXCEPTION 'isolated upgrade database required'; END IF;
 INSERT INTO custody_tenant(id,slug,name,derivation_namespace) VALUES (tenant,'upgrade-fixture','Synthetic upgrade fixture',nextval('custody_derivation_namespace_seq'));
 INSERT INTO chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled) VALUES (tenant,'ETH','ETH','upgrade-principal',43,1,0,source,source,'m/44/60/43/1/0','DEPOSIT',true) RETURNING id INTO chain_address;
 INSERT INTO custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) VALUES (address_id,tenant,chain_address,'ETH','sepolia',source,'user:43','API','ACTIVE',43,0);
 INSERT INTO ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) VALUES (tenant,'ETH','ETH','upgrade-principal',1,1,2);
 INSERT INTO withdrawal_order(tenant_id,order_no,user_id,chain,asset_symbol,from_address,debit_account_id,to_address,amount,fee,status,tx_hash) VALUES (tenant,'upgrade-withdrawal',43,'ETH','ETH',source,'upgrade-principal','synthetic-recipient',1,0,'SENT','0x'||repeat('ab',32)) RETURNING id INTO order_id;
 INSERT INTO custody_withdrawal(id,tenant_id,custody_address_id,withdrawal_order_id,order_no,external_reference,idempotency_key,chain,asset_symbol,to_address,amount,fee,status,created_by_type) VALUES ('cccccccc-0000-0000-0000-000000000043',tenant,address_id,order_id,'upgrade-withdrawal','upgrade-withdrawal','upgrade-withdrawal','ETH','ETH','synthetic-recipient',1,0,'SENT','API_KEY');
 INSERT INTO custody_idempotency_key(tenant_id,idempotency_key,operation,request_hash,response_status,response_body,expires_at) VALUES (tenant,'upgrade-withdrawal','WITHDRAWAL.CREATE',repeat('a',64),202,'{"id":"cccccccc-0000-0000-0000-000000000043"}',now()+interval '7 days');
END $$;
