\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = pg_temp, public;
CREATE TEMP TABLE gateway_users (user_id BIGINT PRIMARY KEY);
\echo '--- apply current 20260805 then 20260806 twice ---'
\i migrations/20260805_gateway_product_transfer.sql
\i migrations/20260806_gateway_product_transfer_events.sql
\i migrations/20260806_gateway_product_transfer_events.sql
SELECT to_regclass('pg_temp.gateway_product_transfers') AS transfers,
       to_regclass('pg_temp.gateway_product_transfer_events') AS events,
       to_regclass('pg_temp.gateway_product_transfer_event_seq') AS event_seq;
SELECT conname
  FROM pg_constraint
 WHERE conrelid = 'pg_temp.gateway_product_transfer_events'::regclass
   AND contype = 'f';
SELECT indexrelid::regclass
  FROM pg_index
 WHERE indrelid = 'pg_temp.gateway_product_transfer_events'::regclass;
ROLLBACK;

BEGIN;
SET LOCAL search_path = pg_temp, public;
CREATE TEMP TABLE gateway_users (user_id BIGINT PRIMARY KEY);
CREATE TEMP SEQUENCE gateway_product_transfer_seq;
CREATE TEMP TABLE gateway_product_transfers (transfer_id BIGINT PRIMARY KEY);
\echo '--- apply forward supplement to legacy transfer table twice ---'
\i migrations/20260806_gateway_product_transfer_events.sql
\i migrations/20260806_gateway_product_transfer_events.sql
SELECT to_regclass('pg_temp.gateway_product_transfer_events') AS events,
       to_regclass('pg_temp.gateway_product_transfer_event_seq') AS event_seq;
ROLLBACK;
