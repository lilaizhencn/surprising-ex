-- Only for a NEW, isolated local database before Core has accepted any orders.
-- Correct the imported catalog's 10^12 price units to each asset's actual scale.
BEGIN;
CREATE TEMP TABLE local_symbols(symbol TEXT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO local_symbols VALUES ('BTC-USDT-SWAP'),('ETH-USDT-SWAP'),('SOL-USDT-SWAP'),('XRP-USDT-SWAP'),('DOGE-USDT-SWAP'),('ADA-USDT-SWAP'),('BNB-USDT-SWAP'),('AVAX-USDT-SWAP'),('LINK-USDT-SWAP'),('DOT-USDT-SWAP'),('LTC-USDT-SWAP'),('BCH-USDT-SWAP'),('UNI-USDT-SWAP'),('AAVE-USDT-SWAP'),('NEAR-USDT-SWAP'),('SUI-USDT-SWAP'),('TRX-USDT-SWAP'),('OP-USDT-SWAP'),('ETC-USDT-SWAP'),('FIL-USDT-SWAP');
DO $$ BEGIN
IF (SELECT count(*) FROM instruments i JOIN local_symbols s USING(symbol) WHERE product_line='LINEAR_PERPETUAL') <> 20
THEN RAISE EXCEPTION 'Local perpetual catalog must contain exactly 20 instruments'; END IF;
END $$;
UPDATE instruments SET status='PRE_TRADING' WHERE product_line='LINEAR_PERPETUAL' AND symbol NOT IN (SELECT symbol FROM local_symbols);
UPDATE instrument_index_sources SET enabled=false WHERE product_line='LINEAR_PERPETUAL';
UPDATE instrument_index_sources SET enabled=true WHERE product_line='LINEAR_PERPETUAL' AND symbol IN (SELECT symbol FROM local_symbols);
INSERT INTO instrument_index_sources (
 symbol,product_line,source,enabled,base_url,path,source_symbol,parser,quote_currency,target_quote_currency,
 conversion_mode,conversion_operation,fallback_weight_multiplier_ppm,websocket_enabled,websocket_url,websocket_subscribe_message,websocket_parser,weight_ppm)
SELECT s.symbol,t.product_line,t.source,true,t.base_url,
 replace(t.path,'BTCUSDT',replace(s.symbol,'-USDT-SWAP','USDT')),
 replace(s.symbol,'-USDT-SWAP','USDT'),t.parser,t.quote_currency,t.target_quote_currency,
 t.conversion_mode,t.conversion_operation,t.fallback_weight_multiplier_ppm,true,t.websocket_url,
 replace(replace(t.websocket_subscribe_message,'BTCUSDT',replace(s.symbol,'-USDT-SWAP','USDT')),'btcusdt',lower(replace(s.symbol,'-USDT-SWAP','USDT'))),
 t.websocket_parser,t.weight_ppm
FROM local_symbols s CROSS JOIN instrument_index_sources t
WHERE t.symbol='BTC-USDT-SWAP' AND t.product_line='LINEAR_PERPETUAL' AND t.source IN ('BINANCE','BYBIT')
ON CONFLICT(symbol,product_line,source) DO NOTHING;
-- One Binance request for all streams; Bybit spot accepts at most ten arguments.
WITH subscription AS (
 SELECT jsonb_build_object('method','SUBSCRIBE','id',1,'params',jsonb_agg(lower(source_symbol)||'@ticker' ORDER BY symbol))::text AS message
 FROM instrument_index_sources WHERE product_line='LINEAR_PERPETUAL' AND source='BINANCE' AND enabled
)
UPDATE instrument_index_sources s SET websocket_subscribe_message=b.message FROM subscription b
WHERE s.product_line='LINEAR_PERPETUAL' AND s.source='BINANCE' AND s.enabled;
WITH ranked AS (
 SELECT symbol,source_symbol,(row_number() OVER(ORDER BY symbol)-1)/10 AS batch
 FROM instrument_index_sources WHERE product_line='LINEAR_PERPETUAL' AND source='BYBIT' AND enabled
), batches AS (
 SELECT batch,jsonb_build_object('op','subscribe','args',jsonb_agg('tickers.'||source_symbol ORDER BY symbol))::text AS message FROM ranked GROUP BY batch
)
UPDATE instrument_index_sources s SET websocket_subscribe_message=b.message FROM ranked r JOIN batches b USING(batch)
WHERE s.symbol=r.symbol AND s.product_line='LINEAR_PERPETUAL' AND s.source='BYBIT';
WITH subscription AS (
 SELECT jsonb_build_object('op','subscribe','args',jsonb_agg(jsonb_build_object('channel','index-tickers','instId',source_symbol) ORDER BY symbol))::text AS message
 FROM instrument_index_sources WHERE product_line='LINEAR_PERPETUAL' AND source='OKX' AND enabled
)
UPDATE instrument_index_sources s SET websocket_subscribe_message=b.message FROM subscription b
WHERE s.product_line='LINEAR_PERPETUAL' AND s.source='OKX' AND s.enabled;
UPDATE instruments i SET
 contract_multiplier_ppm=(1000000 / power(10::numeric,i.quantity_precision))::bigint,
 price_tick_units=(q.scale_units / power(10::numeric,i.price_precision))::bigint,
 quantity_step_units=(b.scale_units / power(10::numeric,i.quantity_precision))::bigint,
 notional_multiplier_units=(q.scale_units / power(10::numeric,i.price_precision+i.quantity_precision))::bigint,
 min_valid_index_sources=3
FROM account_asset_scales q,account_asset_scales b
WHERE i.product_line='LINEAR_PERPETUAL' AND i.symbol IN (SELECT symbol FROM local_symbols)
AND q.asset=i.quote_asset AND b.asset=i.base_asset;
-- A linear contract's displayed base quantity must equal the quantity used by Core notional math.
DO $$ BEGIN
IF EXISTS (SELECT 1 FROM instruments i JOIN local_symbols s USING(symbol)
 WHERE product_line='LINEAR_PERPETUAL' AND (contract_multiplier_ppm <= 0 OR
 price_tick_units::numeric * contract_multiplier_ppm <> notional_multiplier_units::numeric * 1000000))
THEN RAISE EXCEPTION 'Local linear contract size does not match Core notional multiplier'; END IF;
END $$;
-- This is an initial catalog, not an in-place migration of live trading state.
UPDATE instrument_change_log l SET after_values=(to_jsonb(i)-'change_id'-'last_change_id') || jsonb_build_object(
 'priceTickUnits',i.price_tick_units,'quantityStepUnits',i.quantity_step_units,'baseAsset',i.base_asset,'quoteAsset',i.quote_asset,
 'riskLimitBrackets',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY bracket_no) FROM instrument_risk_brackets r WHERE r.product_line=i.product_line AND r.symbol=i.symbol),'[]'::jsonb),
 'indexSources',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY source) FROM instrument_index_sources r WHERE r.product_line=i.product_line AND r.symbol=i.symbol),'[]'::jsonb)),
 reason='Local 20-symbol initial catalog with asset-scale price and quantity units'
FROM instruments i WHERE l.product_line=i.product_line AND l.symbol=i.symbol AND l.change_id=i.change_id
AND i.product_line='LINEAR_PERPETUAL';
COMMIT;
