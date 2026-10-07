-- Surprising Exchange PostgreSQL production baseline.
-- PostgreSQL 18+; execute once on an empty database with psql.
-- This file contains the complete pre-launch schema and required seed data.
-- Pre-launch: maintain this single initialization file; no upgrade migrations.

\set ON_ERROR_STOP on

BEGIN;

-- 币种是账务身份；网络是币种的链上入口，不能各建一份账户余额。
CREATE TABLE assets (
    asset_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (asset_id > 0),
    asset TEXT NOT NULL UNIQUE CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    display_name TEXT NOT NULL DEFAULT '',
    logo_url TEXT NOT NULL DEFAULT '',
    scale_units BIGINT NOT NULL CHECK (scale_units IN (1,10,100,1000,10000,100000,1000000,10000000,100000000,1000000000,10000000000,100000000000,1000000000000,10000000000000,100000000000000,1000000000000000,10000000000000000,100000000000000000,1000000000000000000)),
    listed BOOLEAN NOT NULL DEFAULT FALSE,
    trading_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (listed OR NOT trading_enabled)
);
COMMENT ON TABLE assets IS '全产品线共用币种目录；账务精度唯一来源；永久 ID 不随显示信息变化';
CREATE TABLE asset_networks (
    network_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (network_id > 0),
    asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    network_code TEXT NOT NULL CHECK (network_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    display_name TEXT NOT NULL,
    contract_address TEXT NOT NULL DEFAULT '',
    native_asset BOOLEAN NOT NULL,
    chain_decimals INTEGER NOT NULL CHECK (chain_decimals BETWEEN 0 AND 36),
    deposit_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    min_deposit NUMERIC NOT NULL CHECK (min_deposit > 0),
    min_withdrawal NUMERIC NOT NULL CHECK (min_withdrawal > 0),
    withdrawal_fee NUMERIC NOT NULL CHECK (withdrawal_fee >= 0),
    confirmations INTEGER NOT NULL CHECK (confirmations > 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, network_code, contract_address),
    CHECK ((native_asset AND contract_address = '') OR (NOT native_asset AND length(trim(contract_address)) > 0))
);
CREATE UNIQUE INDEX asset_networks_one_active_route ON asset_networks(asset_id,network_code) WHERE deposit_enabled OR withdrawal_enabled;
COMMENT ON TABLE asset_networks IS '币种的一对多充提网络；网络关闭不改变币种余额和历史流水';
CREATE TABLE asset_configuration_changes (
    change_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    network_id INTEGER REFERENCES asset_networks(network_id),
    operator_id BIGINT NOT NULL,
    reason TEXT NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    before_values JSONB,
    after_values JSONB NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE asset_configuration_changes IS '币种与充提网络配置的事务内审计；不包含用户凭据';
CREATE FUNCTION reject_asset_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.asset_id IS DISTINCT FROM NEW.asset_id OR OLD.asset IS DISTINCT FROM NEW.asset
       OR OLD.scale_units IS DISTINCT FROM NEW.scale_units THEN
        RAISE EXCEPTION 'asset identity, accounting code and scale are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assets_identity_immutable BEFORE UPDATE ON assets
FOR EACH ROW EXECUTE FUNCTION reject_asset_identity_change();
CREATE FUNCTION reject_asset_network_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.network_id IS DISTINCT FROM NEW.network_id OR OLD.asset_id IS DISTINCT FROM NEW.asset_id
       OR OLD.network_code IS DISTINCT FROM NEW.network_code OR OLD.contract_address IS DISTINCT FROM NEW.contract_address
       OR OLD.native_asset IS DISTINCT FROM NEW.native_asset OR OLD.chain_decimals IS DISTINCT FROM NEW.chain_decimals THEN
        RAISE EXCEPTION 'network identity, asset mapping, contract and chain units are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER asset_networks_identity_immutable BEFORE UPDATE ON asset_networks
FOR EACH ROW EXECUTE FUNCTION reject_asset_network_identity_change();
COMMENT ON COLUMN assets.asset_id IS '永久币种 ID';
COMMENT ON COLUMN assets.asset IS '不可变账务代码；对外展示使用名称字段';
COMMENT ON COLUMN assets.display_name IS '可修改的显示名称';
COMMENT ON COLUMN assets.logo_url IS '币种图标地址';
COMMENT ON COLUMN assets.scale_units IS '不可变账务最小单位倍率，10 的整数次幂';
COMMENT ON COLUMN assets.listed IS '币种是否上线';
COMMENT ON COLUMN assets.trading_enabled IS '是否允许配置新市场或启用交易';
COMMENT ON COLUMN assets.revision IS '配置版本；修改必须匹配以避免覆盖他人更改';
COMMENT ON COLUMN assets.created_at IS '创建时间';
COMMENT ON COLUMN assets.updated_at IS '更新时间';
COMMENT ON COLUMN asset_networks.network_id IS '永久充提网络配置 ID';
COMMENT ON COLUMN asset_networks.asset_id IS '所属币种永久 ID';
COMMENT ON COLUMN asset_networks.network_code IS '钱包网络标识';
COMMENT ON COLUMN asset_networks.display_name IS '网络显示名称';
COMMENT ON COLUMN asset_networks.contract_address IS '链上代币合约地址；原生币为空';
COMMENT ON COLUMN asset_networks.native_asset IS '是否为该网络原生币';
COMMENT ON COLUMN asset_networks.chain_decimals IS '链上金额小数位数，独立于账务精度';
COMMENT ON COLUMN asset_networks.deposit_enabled IS '充值开关';
COMMENT ON COLUMN asset_networks.withdrawal_enabled IS '提现开关';
COMMENT ON COLUMN asset_networks.min_deposit IS '最小充值数量，币单位';
COMMENT ON COLUMN asset_networks.min_withdrawal IS '最小提现数量，币单位';
COMMENT ON COLUMN asset_networks.withdrawal_fee IS '提现手续费，币单位';
COMMENT ON COLUMN asset_networks.confirmations IS '到账所需确认数';
COMMENT ON COLUMN asset_networks.revision IS '乐观锁配置版本';
COMMENT ON COLUMN asset_networks.created_at IS '创建时间';
COMMENT ON COLUMN asset_networks.updated_at IS '更新时间';
COMMENT ON COLUMN asset_configuration_changes.change_id IS '审计 ID';
COMMENT ON COLUMN asset_configuration_changes.asset_id IS '币种 ID';
COMMENT ON COLUMN asset_configuration_changes.network_id IS '网络 ID；币种基础配置修改为空';
COMMENT ON COLUMN asset_configuration_changes.operator_id IS '已认证管理员 ID';
COMMENT ON COLUMN asset_configuration_changes.reason IS '修改原因';
COMMENT ON COLUMN asset_configuration_changes.before_values IS '修改前配置';
COMMENT ON COLUMN asset_configuration_changes.after_values IS '修改后配置';
COMMENT ON COLUMN asset_configuration_changes.changed_at IS '修改时间';

INSERT INTO assets (asset, scale_units, created_at, updated_at)
VALUES
('USDT', 100000000, now(), now()),
('USD', 100000000, now(), now()),
('BTC', 100000000, now(), now()),
('ETH', 1000000000000000000, now(), now())
ON CONFLICT (asset) DO NOTHING;

INSERT INTO assets (asset, scale_units, created_at, updated_at) VALUES
('0G', 100000000, now(), now()),
('1INCH', 100000000, now(), now()),
('2Z', 100000000, now(), now()),
('AAOI', 100000000, now(), now()),
('AAPL', 100000000, now(), now()),
('AAVE', 100000000, now(), now()),
('ACE', 100000000, now(), now()),
('ACH', 100000000, now(), now()),
('ACT', 100000000, now(), now()),
('ACU', 100000000, now(), now()),
('ADA', 100000000, now(), now()),
('ADBE', 100000000, now(), now()),
('AED', 100000000, now(), now()),
('AEHR', 100000000, now(), now()),
('AEON', 100000000, now(), now()),
('AERO', 100000000, now(), now()),
('AEVO', 100000000, now(), now()),
('AGLD', 100000000, now(), now()),
('AI', 100000000, now(), now()),
('AIXBT', 100000000, now(), now()),
('ALAB', 100000000, now(), now()),
('ALGO', 100000000, now(), now()),
('ALLO', 100000000, now(), now()),
('AMAT', 100000000, now(), now()),
('AMD', 100000000, now(), now()),
('AMZN', 100000000, now(), now()),
('ANIME', 100000000, now(), now()),
('ANTHROPIC', 100000000, now(), now()),
('APE', 100000000, now(), now()),
('API3', 100000000, now(), now()),
('APLD', 100000000, now(), now()),
('APP', 100000000, now(), now()),
('APR', 100000000, now(), now()),
('APT', 100000000, now(), now()),
('AR', 100000000, now(), now()),
('ARB', 100000000, now(), now()),
('ARG', 100000000, now(), now()),
('ARKM', 100000000, now(), now()),
('ARM', 100000000, now(), now()),
('ARX', 100000000, now(), now()),
('ASML', 100000000, now(), now()),
('ASP', 100000000, now(), now()),
('ASTER', 100000000, now(), now()),
('ASTR', 100000000, now(), now()),
('ASTS', 100000000, now(), now()),
('AT', 100000000, now(), now()),
('ATH', 100000000, now(), now()),
('ATOM', 100000000, now(), now()),
('AUCTION', 100000000, now(), now()),
('AUD', 100000000, now(), now()),
('AUDF', 100000000, now(), now()),
('AUDM', 100000000, now(), now()),
('AVAX', 100000000, now(), now()),
('AVGO', 100000000, now(), now()),
('AVNT', 100000000, now(), now()),
('AXS', 100000000, now(), now()),
('AXTI', 100000000, now(), now()),
('AZTEC', 100000000, now(), now()),
('BABY', 100000000, now(), now()),
('BABYDOGE', 100000000, now(), now()),
('BANANA', 100000000, now(), now()),
('BAND', 100000000, now(), now()),
('BARD', 100000000, now(), now()),
('BASED', 100000000, now(), now()),
('BAT', 100000000, now(), now()),
('BB', 100000000, now(), now()),
('BCH', 100000000, now(), now()),
('BE', 100000000, now(), now()),
('BEAT', 100000000, now(), now()),
('BERA', 100000000, now(), now()),
('BETH', 100000000, now(), now()),
('BICO', 100000000, now(), now()),
('BIGTIME', 100000000, now(), now()),
('BILL', 100000000, now(), now()),
('BIO', 100000000, now(), now()),
('BLEND', 100000000, now(), now()),
('BLUR', 100000000, now(), now()),
('BMNR', 100000000, now(), now()),
('BNB', 100000000, now(), now()),
('BNT', 100000000, now(), now()),
('BOME', 100000000, now(), now()),
('BONK', 100000000, now(), now()),
('BOT', 100000000, now(), now()),
('BRETT', 100000000, now(), now()),
('BREV', 100000000, now(), now()),
('BRKB', 100000000, now(), now()),
('BRL', 100000000, now(), now()),
('BRL1', 100000000, now(), now()),
('BSB', 100000000, now(), now()),
('BSP', 100000000, now(), now()),
('BTC', 100000000, now(), now()),
('BX', 100000000, now(), now()),
('BZ', 100000000, now(), now()),
('CAP', 100000000, now(), now()),
('CARDS', 100000000, now(), now()),
('CAT', 100000000, now(), now()),
('CATI', 100000000, now(), now()),
('CBRS', 100000000, now(), now()),
('CC', 100000000, now(), now()),
('CELO', 100000000, now(), now()),
('CELR', 100000000, now(), now()),
('CETUS', 100000000, now(), now()),
('CFG', 100000000, now(), now()),
('CFX', 100000000, now(), now()),
('CGNX', 100000000, now(), now()),
('CHIP', 100000000, now(), now()),
('CHZ', 100000000, now(), now()),
('CIEN', 100000000, now(), now()),
('CITY', 100000000, now(), now()),
('CL', 100000000, now(), now()),
('COAI', 100000000, now(), now()),
('COHR', 100000000, now(), now()),
('COIN', 100000000, now(), now()),
('COMP', 100000000, now(), now()),
('CORE', 100000000, now(), now()),
('COST', 100000000, now(), now()),
('CRCL', 100000000, now(), now()),
('CRDO', 100000000, now(), now()),
('CRM', 100000000, now(), now()),
('CRO', 100000000, now(), now()),
('CRV', 100000000, now(), now()),
('CRWD', 100000000, now(), now()),
('CRWV', 100000000, now(), now()),
('CSCO', 100000000, now(), now()),
('CSPR', 100000000, now(), now()),
('CTC', 100000000, now(), now()),
('CVC', 100000000, now(), now()),
('CVX', 100000000, now(), now()),
('CXMT', 100000000, now(), now()),
('DASH', 100000000, now(), now()),
('DATA', 100000000, now(), now()),
('DEGEN', 100000000, now(), now()),
('DELL', 100000000, now(), now()),
('DGB', 100000000, now(), now()),
('DKNG', 100000000, now(), now()),
('DOGE', 100000000, now(), now()),
('DOGS', 100000000, now(), now()),
('DOOD', 100000000, now(), now()),
('DORA', 100000000, now(), now()),
('DOS', 100000000, now(), now()),
('DOT', 100000000, now(), now()),
('DRAM', 100000000, now(), now()),
('DYDX', 100000000, now(), now()),
('EDEN', 100000000, now(), now()),
('EDGE', 100000000, now(), now()),
('EGLD', 100000000, now(), now()),
('EIGEN', 100000000, now(), now()),
('ELF', 100000000, now(), now()),
('ENA', 100000000, now(), now()),
('ENJ', 100000000, now(), now()),
('ENS', 100000000, now(), now()),
('ENSO', 100000000, now(), now()),
('ESP', 100000000, now(), now()),
('ETC', 100000000, now(), now()),
('ETH', 1000000000000000000, now(), now()),
('ETHFI', 100000000, now(), now()),
('ETHW', 100000000, now(), now()),
('EUR', 100000000, now(), now()),
('EURC', 100000000, now(), now()),
('EWJ', 100000000, now(), now()),
('EWT', 100000000, now(), now()),
('EWY', 100000000, now(), now()),
('EWZ', 100000000, now(), now()),
('FARTCOIN', 100000000, now(), now()),
('FET', 100000000, now(), now()),
('FIL', 100000000, now(), now()),
('FLNC', 100000000, now(), now()),
('FLOKI', 100000000, now(), now()),
('FLOW', 100000000, now(), now()),
('FLR', 100000000, now(), now()),
('FLUID', 100000000, now(), now()),
('FLY', 100000000, now(), now()),
('FOGO', 100000000, now(), now()),
('FWDI', 100000000, now(), now()),
('GALA', 100000000, now(), now()),
('GALFT', 100000000, now(), now()),
('GAS', 100000000, now(), now()),
('GEV', 100000000, now(), now()),
('GIGGLE', 100000000, now(), now()),
('GLM', 100000000, now(), now()),
('GLW', 100000000, now(), now()),
('GME', 100000000, now(), now()),
('GMT', 100000000, now(), now()),
('GMX', 100000000, now(), now()),
('GOAT', 100000000, now(), now()),
('GOOGL', 100000000, now(), now()),
('GPS', 100000000, now(), now()),
('GRAM', 100000000, now(), now()),
('GRASS', 100000000, now(), now()),
('GRT', 100000000, now(), now()),
('GRVT', 100000000, now(), now()),
('HBAR', 100000000, now(), now()),
('HIMS', 100000000, now(), now()),
('HMSTR', 100000000, now(), now()),
('HOME', 100000000, now(), now()),
('HOOD', 100000000, now(), now()),
('HPE', 100000000, now(), now()),
('HUMA', 100000000, now(), now()),
('HYPE', 100000000, now(), now()),
('HYUNDAI', 100000000, now(), now()),
('IBM', 100000000, now(), now()),
('ICP', 100000000, now(), now()),
('ICX', 100000000, now(), now()),
('ID', 100000000, now(), now()),
('ILV', 100000000, now(), now()),
('IMX', 100000000, now(), now()),
('INFQ', 100000000, now(), now()),
('INIT', 100000000, now(), now()),
('INJ', 100000000, now(), now()),
('INTC', 100000000, now(), now()),
('INTW', 100000000, now(), now()),
('IOST', 100000000, now(), now()),
('IOTA', 100000000, now(), now()),
('IREN', 100000000, now(), now()),
('IRYS', 100000000, now(), now()),
('ISRG', 100000000, now(), now()),
('IWM', 100000000, now(), now()),
('JELLYJELLY', 100000000, now(), now()),
('JITOSOL', 100000000, now(), now()),
('JNJ', 100000000, now(), now()),
('JOE', 100000000, now(), now()),
('JTO', 100000000, now(), now()),
('JUP', 100000000, now(), now()),
('KAIA', 100000000, now(), now()),
('KAITO', 100000000, now(), now()),
('KAT', 100000000, now(), now()),
('KGEN', 100000000, now(), now()),
('KIOXIA', 100000000, now(), now()),
('KITE', 100000000, now(), now()),
('KLAC', 100000000, now(), now()),
('KMNO', 100000000, now(), now()),
('KNC', 100000000, now(), now()),
('KO', 100000000, now(), now()),
('KORU', 100000000, now(), now()),
('KR200', 100000000, now(), now()),
('KSM', 100000000, now(), now()),
('LA', 100000000, now(), now()),
('LAB', 100000000, now(), now()),
('LAT', 100000000, now(), now()),
('LAYER', 100000000, now(), now()),
('LDO', 100000000, now(), now()),
('LEO', 100000000, now(), now()),
('LIGHT', 100000000, now(), now()),
('LINEA', 100000000, now(), now()),
('LINK', 100000000, now(), now()),
('LIT', 100000000, now(), now()),
('LITE', 100000000, now(), now()),
('LLY', 100000000, now(), now()),
('LPT', 100000000, now(), now()),
('LQTY', 100000000, now(), now()),
('LRC', 100000000, now(), now()),
('LRCX', 100000000, now(), now()),
('LSK', 100000000, now(), now()),
('LTC', 100000000, now(), now()),
('LUNA', 100000000, now(), now()),
('LUNR', 100000000, now(), now()),
('LYTE', 100000000, now(), now()),
('MAGIC', 100000000, now(), now()),
('MANA', 100000000, now(), now()),
('MASK', 100000000, now(), now()),
('ME', 100000000, now(), now()),
('MEGA', 100000000, now(), now()),
('MEME', 100000000, now(), now()),
('MENGO', 100000000, now(), now()),
('MERL', 100000000, now(), now()),
('MET', 100000000, now(), now()),
('META', 100000000, now(), now()),
('METIS', 100000000, now(), now()),
('MEW', 100000000, now(), now()),
('MINA', 100000000, now(), now()),
('MINIMAX', 100000000, now(), now()),
('MMT', 100000000, now(), now()),
('MON', 100000000, now(), now()),
('MOODENG', 100000000, now(), now()),
('MOONSHOT', 100000000, now(), now()),
('MORPHO', 100000000, now(), now()),
('MOVE', 100000000, now(), now()),
('MRVL', 100000000, now(), now()),
('MSFT', 100000000, now(), now()),
('MSTR', 100000000, now(), now()),
('MU', 100000000, now(), now()),
('MUBARAK', 100000000, now(), now()),
('MUU', 100000000, now(), now()),
('MVLL', 100000000, now(), now()),
('NAVX', 100000000, now(), now()),
('NBIS', 100000000, now(), now()),
('NEAR', 100000000, now(), now()),
('NEIRO', 100000000, now(), now()),
('NEO', 100000000, now(), now()),
('NES', 100000000, now(), now()),
('NET', 100000000, now(), now()),
('NFLX', 100000000, now(), now()),
('NFT', 100000000, now(), now()),
('NG', 100000000, now(), now()),
('NIGHT', 100000000, now(), now()),
('NMR', 100000000, now(), now()),
('NOK', 100000000, now(), now()),
('NOT', 100000000, now(), now()),
('NOW', 100000000, now(), now()),
('NVDA', 100000000, now(), now()),
('OFC', 100000000, now(), now()),
('OKB', 100000000, now(), now()),
('OKSOL', 100000000, now(), now()),
('OKTA', 100000000, now(), now()),
('OL', 100000000, now(), now()),
('OMI', 100000000, now(), now()),
('ON', 100000000, now(), now()),
('ONDO', 100000000, now(), now()),
('ONDS', 100000000, now(), now()),
('ONE', 100000000, now(), now()),
('ONT', 100000000, now(), now()),
('OP', 100000000, now(), now()),
('OPENAI', 100000000, now(), now()),
('OPG', 100000000, now(), now()),
('OPN', 100000000, now(), now()),
('ORBS', 100000000, now(), now()),
('ORCL', 100000000, now(), now()),
('ORDER', 100000000, now(), now()),
('ORDI', 100000000, now(), now()),
('OSCR', 100000000, now(), now()),
('OUST', 100000000, now(), now()),
('PARTI', 100000000, now(), now()),
('PAXG', 100000000, now(), now()),
('PENDLE', 100000000, now(), now()),
('PENG', 100000000, now(), now()),
('PENGU', 100000000, now(), now()),
('PEOPLE', 100000000, now(), now()),
('PEPE', 100000000, now(), now()),
('PHA', 100000000, now(), now()),
('PI', 100000000, now(), now()),
('PIEVERSE', 100000000, now(), now()),
('PIPPIN', 100000000, now(), now()),
('PIXEL', 100000000, now(), now()),
('PLTR', 100000000, now(), now()),
('PLUME', 100000000, now(), now()),
('PNUT', 100000000, now(), now()),
('POET', 100000000, now(), now()),
('POL', 100000000, now(), now()),
('POPCAT', 100000000, now(), now()),
('POPMART', 100000000, now(), now()),
('POR', 100000000, now(), now()),
('PROMPT', 100000000, now(), now()),
('PROS', 100000000, now(), now()),
('PROVE', 100000000, now(), now()),
('PUMP', 100000000, now(), now()),
('PYTH', 100000000, now(), now()),
('PYUSD', 100000000, now(), now()),
('QCOM', 100000000, now(), now()),
('QNT', 100000000, now(), now()),
('QQQ', 100000000, now(), now()),
('QTUM', 100000000, now(), now()),
('RAM', 100000000, now(), now()),
('RAVE', 100000000, now(), now()),
('RAY', 100000000, now(), now()),
('RDDT', 100000000, now(), now()),
('RDW', 100000000, now(), now()),
('RE', 100000000, now(), now()),
('RECALL', 100000000, now(), now()),
('RENDER', 100000000, now(), now()),
('RESOLV', 100000000, now(), now()),
('RIOT', 100000000, now(), now()),
('RIVER', 100000000, now(), now()),
('RIVN', 100000000, now(), now()),
('RKLB', 100000000, now(), now()),
('RLS', 100000000, now(), now()),
('RLUSD', 100000000, now(), now()),
('ROBO', 100000000, now(), now()),
('ROK', 100000000, now(), now()),
('RON', 100000000, now(), now()),
('RPL', 100000000, now(), now()),
('RSR', 100000000, now(), now()),
('RVN', 100000000, now(), now()),
('SAFE', 100000000, now(), now()),
('SAHARA', 100000000, now(), now()),
('SAMSUNG', 100000000, now(), now()),
('SAND', 100000000, now(), now()),
('SAPIEN', 100000000, now(), now()),
('SATS', 100000000, now(), now()),
('SCR', 100000000, now(), now()),
('SD', 100000000, now(), now()),
('SEI', 100000000, now(), now()),
('SENT', 100000000, now(), now()),
('SGD', 100000000, now(), now()),
('SHAZ', 100000000, now(), now()),
('SHELL', 100000000, now(), now()),
('SHIB', 100000000, now(), now()),
('SHLD', 100000000, now(), now()),
('SHOP', 100000000, now(), now()),
('SIGN', 100000000, now(), now()),
('SIMO', 100000000, now(), now()),
('SKDD', 100000000, now(), now()),
('SKHY', 100000000, now(), now()),
('SKHYNIX', 100000000, now(), now()),
('SKL', 100000000, now(), now()),
('SKUU', 100000000, now(), now()),
('SKY', 100000000, now(), now()),
('SLP', 100000000, now(), now()),
('SLX', 100000000, now(), now()),
('SMCI', 100000000, now(), now()),
('SMH', 100000000, now(), now()),
('SNDK', 100000000, now(), now()),
('SNOW', 100000000, now(), now()),
('SNT', 100000000, now(), now()),
('SNX', 100000000, now(), now()),
('SNXX', 100000000, now(), now()),
('SOFTBANK', 100000000, now(), now()),
('SOL', 100000000, now(), now()),
('SONIC', 100000000, now(), now()),
('SONY', 100000000, now(), now()),
('SOON', 100000000, now(), now()),
('SOPH', 100000000, now(), now()),
('SOXL', 100000000, now(), now()),
('SOXS', 100000000, now(), now()),
('SPACE', 100000000, now(), now()),
('SPCX', 100000000, now(), now()),
('SPK', 100000000, now(), now()),
('SPURS', 100000000, now(), now()),
('SPX', 100000000, now(), now()),
('SPY', 100000000, now(), now()),
('SQQQ', 100000000, now(), now()),
('SSV', 100000000, now(), now()),
('STABLE', 100000000, now(), now()),
('STETH', 100000000, now(), now()),
('STORJ', 100000000, now(), now()),
('STRC', 100000000, now(), now()),
('STRK', 100000000, now(), now()),
('STX', 100000000, now(), now()),
('SUI', 100000000, now(), now()),
('SUSHI', 100000000, now(), now()),
('SWFTC', 100000000, now(), now()),
('SYRUP', 100000000, now(), now()),
('TAO', 100000000, now(), now()),
('TER', 100000000, now(), now()),
('TEV', 100000000, now(), now()),
('THETA', 100000000, now(), now()),
('TIA', 100000000, now(), now()),
('TMF', 100000000, now(), now()),
('TNSR', 100000000, now(), now()),
('TOSHI', 100000000, now(), now()),
('TQQQ', 100000000, now(), now()),
('TRA', 100000000, now(), now()),
('TRB', 100000000, now(), now()),
('TRIA', 100000000, now(), now()),
('TRUMP', 100000000, now(), now()),
('TRUST', 100000000, now(), now()),
('TRUTH', 100000000, now(), now()),
('TRX', 100000000, now(), now()),
('TRY', 100000000, now(), now()),
('TSEM', 100000000, now(), now()),
('TSLA', 100000000, now(), now()),
('TSM', 100000000, now(), now()),
('TTMI', 100000000, now(), now()),
('TTWO', 100000000, now(), now()),
('TURBO', 100000000, now(), now()),
('TWLO', 100000000, now(), now()),
('UB', 100000000, now(), now()),
('UMA', 100000000, now(), now()),
('UNH', 100000000, now(), now()),
('UNI', 100000000, now(), now()),
('UNITREE', 100000000, now(), now()),
('UP', 100000000, now(), now()),
('URNM', 100000000, now(), now()),
('USAR', 100000000, now(), now()),
('USAT', 100000000, now(), now()),
('USD', 100000000, now(), now()),
('USD1', 100000000, now(), now()),
('USDC', 100000000, now(), now()),
('USDG', 100000000, now(), now()),
('USDS', 100000000, now(), now()),
('USDT', 100000000, now(), now()),
('USELESS', 100000000, now(), now()),
('USO', 100000000, now(), now()),
('UVXY', 100000000, now(), now()),
('VANA', 100000000, now(), now()),
('VELO', 100000000, now(), now()),
('VELODROME', 100000000, now(), now()),
('VINE', 100000000, now(), now()),
('VIRTUAL', 100000000, now(), now()),
('VRT', 100000000, now(), now()),
('VVV', 100000000, now(), now()),
('WAL', 100000000, now(), now()),
('WAXP', 100000000, now(), now()),
('WCT', 100000000, now(), now()),
('WDC', 100000000, now(), now()),
('WEN', 100000000, now(), now()),
('WET', 100000000, now(), now()),
('WIF', 100000000, now(), now()),
('WIN', 100000000, now(), now()),
('WLD', 100000000, now(), now()),
('WLFI', 100000000, now(), now()),
('WOO', 100000000, now(), now()),
('XAAOI', 100000000, now(), now()),
('XAAPL', 100000000, now(), now()),
('XADBE', 100000000, now(), now()),
('XAG', 100000000, now(), now()),
('XALAB', 100000000, now(), now()),
('XAMAT', 100000000, now(), now()),
('XAMD', 100000000, now(), now()),
('XAMZN', 100000000, now(), now()),
('XAPLD', 100000000, now(), now()),
('XAPP', 100000000, now(), now()),
('XARM', 100000000, now(), now()),
('XASML', 100000000, now(), now()),
('XASTS', 100000000, now(), now()),
('XAU', 100000000, now(), now()),
('XAUT', 100000000, now(), now()),
('XAVGO', 100000000, now(), now()),
('XBE', 100000000, now(), now()),
('XBI', 100000000, now(), now()),
('XBMNR', 100000000, now(), now()),
('XBOT', 100000000, now(), now()),
('XBSP', 100000000, now(), now()),
('XBX', 100000000, now(), now()),
('XCBRS', 100000000, now(), now()),
('XCH', 100000000, now(), now()),
('XCIEN', 100000000, now(), now()),
('XCOHR', 100000000, now(), now()),
('XCOIN', 100000000, now(), now()),
('XCRCL', 100000000, now(), now()),
('XCRM', 100000000, now(), now()),
('XCRWD', 100000000, now(), now()),
('XCRWV', 100000000, now(), now()),
('XCSCO', 100000000, now(), now()),
('XCU', 100000000, now(), now()),
('XDELL', 100000000, now(), now()),
('XDKNG', 100000000, now(), now()),
('XEWY', 100000000, now(), now()),
('XGEV', 100000000, now(), now()),
('XGME', 100000000, now(), now()),
('XGOOGL', 100000000, now(), now()),
('XHIMS', 100000000, now(), now()),
('XHOOD', 100000000, now(), now()),
('XHPE', 100000000, now(), now()),
('XIAOMI', 100000000, now(), now()),
('XIBM', 100000000, now(), now()),
('XINTC', 100000000, now(), now()),
('XIREN', 100000000, now(), now()),
('XISRG', 100000000, now(), now()),
('XIWM', 100000000, now(), now()),
('XKO', 100000000, now(), now()),
('XLE', 100000000, now(), now()),
('XLITE', 100000000, now(), now()),
('XLLY', 100000000, now(), now()),
('XLM', 100000000, now(), now()),
('XLRCX', 100000000, now(), now()),
('XMETA', 100000000, now(), now()),
('XMRVL', 100000000, now(), now()),
('XMSFT', 100000000, now(), now()),
('XMSTR', 100000000, now(), now()),
('XMU', 100000000, now(), now()),
('XNBIS', 100000000, now(), now()),
('XNFLX', 100000000, now(), now()),
('XNOW', 100000000, now(), now()),
('XNVDA', 100000000, now(), now()),
('XOKTA', 100000000, now(), now()),
('XON', 100000000, now(), now()),
('XONDS', 100000000, now(), now()),
('XORCL', 100000000, now(), now()),
('XPD', 100000000, now(), now()),
('XPL', 100000000, now(), now()),
('XPLTR', 100000000, now(), now()),
('XPOPMART', 100000000, now(), now()),
('XPT', 100000000, now(), now()),
('XQQQ', 100000000, now(), now()),
('XRDDT', 100000000, now(), now()),
('XRIVN', 100000000, now(), now()),
('XRKLB', 100000000, now(), now()),
('XRP', 100000000, now(), now()),
('XSHAZ', 100000000, now(), now()),
('XSKHY', 100000000, now(), now()),
('XSMCI', 100000000, now(), now()),
('XSMH', 100000000, now(), now()),
('XSNDK', 100000000, now(), now()),
('XSNOW', 100000000, now(), now()),
('XSOXL', 100000000, now(), now()),
('XSPCX', 100000000, now(), now()),
('XSPY', 100000000, now(), now()),
('XTER', 100000000, now(), now()),
('XTQQQ', 100000000, now(), now()),
('XTSLA', 100000000, now(), now()),
('XTSM', 100000000, now(), now()),
('XTTWO', 100000000, now(), now()),
('XTWLO', 100000000, now(), now()),
('XTZ', 100000000, now(), now()),
('XUNH', 100000000, now(), now()),
('XUSAR', 100000000, now(), now()),
('XVRT', 100000000, now(), now()),
('XXIAOMI', 100000000, now(), now()),
('XXLE', 100000000, now(), now()),
('XZM', 100000000, now(), now()),
('YB', 100000000, now(), now()),
('YFI', 100000000, now(), now()),
('YGG', 100000000, now(), now()),
('ZAMA', 100000000, now(), now()),
('ZBCN', 100000000, now(), now()),
('ZBT', 100000000, now(), now()),
('ZEC', 100000000, now(), now()),
('ZEN', 100000000, now(), now()),
('ZENT', 100000000, now(), now()),
('ZETA', 100000000, now(), now()),
('ZEUS', 100000000, now(), now()),
('ZHIPU', 100000000, now(), now()),
('ZIL', 100000000, now(), now()),
('ZK', 100000000, now(), now()),
('ZKJ', 100000000, now(), now()),
('ZKP', 100000000, now(), now()),
('ZM', 100000000, now(), now()),
('ZORA', 100000000, now(), now()),
('ZRO', 100000000, now(), now()),
('ZRX', 100000000, now(), now())
ON CONFLICT (asset) DO NOTHING;



UPDATE assets SET display_name=asset, listed=true, trading_enabled=true;

-- 01. Instrument definitions, lifecycle metadata and index sources.

CREATE SEQUENCE instrument_identity_sequence AS INTEGER;
CREATE SEQUENCE instrument_change_log_sequence;
CREATE TABLE instrument_change_log (
    product_line TEXT NOT NULL,
    instrument_id INTEGER NOT NULL,
    symbol TEXT NOT NULL,
    change_id BIGINT NOT NULL DEFAULT nextval('instrument_change_log_sequence'),
    operator_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    before_values JSONB,
    after_values JSONB NOT NULL,
    PRIMARY KEY (product_line, instrument_id, change_id)
);
COMMENT ON TABLE instrument_change_log IS 'Instrument 操作日志；只用于审计和事件顺序，不提供历史配置选择';
COMMENT ON COLUMN instrument_change_log.product_line IS '配置所属产品线';
COMMENT ON COLUMN instrument_change_log.symbol IS '被修改的币对';
COMMENT ON COLUMN instrument_change_log.change_id IS '操作日志标识，供事件去重和旧消息检测';
COMMENT ON COLUMN instrument_change_log.operator_id IS '修改配置的管理员或明确标识的系统任务';
COMMENT ON COLUMN instrument_change_log.reason IS '修改原因或关联工单';
COMMENT ON COLUMN instrument_change_log.changed_at IS '修改发生时间';
COMMENT ON COLUMN instrument_change_log.before_values IS '修改前的字段值；首次创建为空';
COMMENT ON COLUMN instrument_change_log.after_values IS '修改后的字段值';

CREATE TABLE IF NOT EXISTS instruments (
    instrument_id INTEGER NOT NULL DEFAULT nextval('instrument_identity_sequence'),
    symbol                      TEXT NOT NULL,
    change_id                     BIGINT NOT NULL,
    last_change_id                BIGINT NOT NULL,
    instrument_type             TEXT NOT NULL,
    contract_type               TEXT NOT NULL,
    base_asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    quote_asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    settle_asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    contract_multiplier_ppm     BIGINT NOT NULL,
    contract_value_asset_id INTEGER NOT NULL REFERENCES assets(asset_id),
    price_tick_units            BIGINT NOT NULL,
    quantity_step_units         BIGINT NOT NULL,
    min_quantity_steps          BIGINT NOT NULL,
    max_quantity_steps          BIGINT NOT NULL,
    min_notional_units          BIGINT NOT NULL,
    max_notional_units          BIGINT NOT NULL,
    notional_multiplier_units   BIGINT NOT NULL,
    price_precision             INTEGER NOT NULL,
    quantity_precision          INTEGER NOT NULL,
    supported_order_types       TEXT NOT NULL,
    supported_time_in_force     TEXT NOT NULL,
    post_only_enabled           BOOLEAN NOT NULL,
    reduce_only_enabled         BOOLEAN NOT NULL,
    market_order_enabled        BOOLEAN NOT NULL,
    max_leverage_ppm            BIGINT NOT NULL,
    initial_margin_rate_ppm     BIGINT NOT NULL,
    maintenance_margin_rate_ppm BIGINT NOT NULL,
    maker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    taker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    max_position_notional_units BIGINT NOT NULL,
    user_open_interest_limit_rate_ppm BIGINT NOT NULL DEFAULT 300000,
    user_open_interest_limit_floor_units BIGINT NOT NULL DEFAULT 25000000000000,
    funding_interval_hours      INTEGER NOT NULL,
    interest_rate_ppm           BIGINT NOT NULL,
    funding_rate_cap_ppm        BIGINT NOT NULL,
    funding_rate_floor_ppm      BIGINT NOT NULL,
    impact_notional_units       BIGINT NOT NULL,
    min_valid_index_sources     INTEGER NOT NULL DEFAULT 3,
    expiry_time                 TIMESTAMPTZ,
    delivery_time               TIMESTAMPTZ,
    underlying_instrument_id INTEGER,
    underlying_product_line TEXT,
    strike_price_units          BIGINT,
    option_type                 TEXT,
    option_exercise_style       TEXT,
    settlement_method           TEXT,
    status                      TEXT NOT NULL,
    effective_time              TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, instrument_id),
    UNIQUE (contract_type, symbol),
    product_line TEXT GENERATED ALWAYS AS (CASE WHEN contract_type='VANILLA_OPTION' THEN 'OPTION' ELSE contract_type END) STORED,
    UNIQUE (product_line, symbol),
    CHECK (change_id > 0 AND last_change_id >= change_id),
    FOREIGN KEY (product_line,instrument_id,last_change_id) REFERENCES instrument_change_log(product_line,instrument_id,change_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (product_line,instrument_id,change_id) REFERENCES instrument_change_log(product_line,instrument_id,change_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT instruments_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$' AND symbol !~ '-SWAP$'),
    CONSTRAINT instruments_type_check CHECK (instrument_type IN ('SPOT', 'PERPETUAL', 'DELIVERY', 'OPTION')),
    CONSTRAINT instruments_contract_type_check CHECK (
        contract_type IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                          'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'VANILLA_OPTION')
    ),
    CONSTRAINT instruments_product_contract_check CHECK (
        (instrument_type = 'SPOT' AND contract_type = 'SPOT')
        OR (instrument_type = 'PERPETUAL' AND contract_type IN ('LINEAR_PERPETUAL', 'INVERSE_PERPETUAL'))
        OR (instrument_type = 'DELIVERY' AND contract_type IN ('LINEAR_DELIVERY', 'INVERSE_DELIVERY'))
        OR (instrument_type = 'OPTION' AND contract_type = 'VANILLA_OPTION')
    ),
    CONSTRAINT instruments_underlying_instrument_id_check CHECK (
        ((underlying_instrument_id IS NULL) = (underlying_product_line IS NULL)) AND (underlying_instrument_id IS NULL OR (underlying_instrument_id > 0 AND underlying_product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY')))
    ),
    CONSTRAINT instruments_expiry_metadata_check CHECK (
        (
            instrument_type IN ('SPOT', 'PERPETUAL')
            AND expiry_time IS NULL
            AND delivery_time IS NULL
            AND settlement_method IS NULL
        )
        OR (
            instrument_type IN ('DELIVERY', 'OPTION')
            AND expiry_time IS NOT NULL
            AND delivery_time IS NOT NULL
            AND delivery_time >= expiry_time
            AND settlement_method IN ('CASH', 'PHYSICAL')
        )
    ),
    CONSTRAINT instruments_option_metadata_check CHECK (
        (
            instrument_type <> 'OPTION'
            AND strike_price_units IS NULL
            AND option_type IS NULL
            AND option_exercise_style IS NULL
        )
        OR (
            instrument_type = 'OPTION'
            AND underlying_instrument_id IS NOT NULL
            AND strike_price_units > 0
            AND option_type IN ('CALL', 'PUT')
            AND option_exercise_style IN ('EUROPEAN', 'AMERICAN')
        )
    ),
    CONSTRAINT instruments_status_check CHECK (status IN ('PRE_TRADING', 'TRADING', 'HALT', 'SETTLING', 'CLOSED', 'DRAFT')),
    CONSTRAINT instruments_positive_values CHECK (
        contract_multiplier_ppm > 0
        AND price_tick_units > 0
        AND quantity_step_units > 0
        AND min_quantity_steps > 0
        AND max_quantity_steps >= min_quantity_steps
        AND min_notional_units > 0
        AND max_notional_units >= min_notional_units
        AND notional_multiplier_units > 0
        AND max_leverage_ppm > 0
        AND initial_margin_rate_ppm > 0
        AND maintenance_margin_rate_ppm > 0
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND max_position_notional_units > 0
        AND user_open_interest_limit_rate_ppm >= 0
        AND user_open_interest_limit_floor_units > 0
        AND funding_interval_hours >= 0
        AND (instrument_type <> 'PERPETUAL' OR funding_interval_hours > 0)
        AND funding_rate_cap_ppm >= funding_rate_floor_ppm
        AND impact_notional_units > 0
        AND min_valid_index_sources > 0
    )
);

CREATE INDEX IF NOT EXISTS instruments_status_idx
    ON instruments (status, instrument_type, symbol);

CREATE INDEX IF NOT EXISTS instruments_updated_page_idx
    ON instruments (updated_at DESC, symbol DESC, change_id DESC);

CREATE INDEX IF NOT EXISTS instruments_created_page_idx
    ON instruments (created_at DESC, symbol DESC, change_id DESC);

ALTER TABLE instrument_change_log ADD FOREIGN KEY(product_line,instrument_id)
REFERENCES instruments(product_line,instrument_id) DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN instruments.base_asset_id IS '基础资产永久币种 ID';
COMMENT ON COLUMN instruments.quote_asset_id IS '计价资产永久币种 ID';
COMMENT ON COLUMN instruments.settle_asset_id IS '结算资产永久币种 ID';
COMMENT ON COLUMN instruments.contract_value_asset_id IS '合约面值资产永久币种 ID';

CREATE TABLE IF NOT EXISTS instrument_risk_brackets (
    instrument_id           INTEGER NOT NULL,
    product_line                 TEXT NOT NULL,
    bracket_no              INTEGER NOT NULL,
    notional_floor_units    BIGINT NOT NULL,
    notional_cap_units      BIGINT NOT NULL,
    max_leverage_ppm        BIGINT NOT NULL,
    initial_margin_rate_ppm BIGINT NOT NULL,
    maintenance_margin_rate_ppm BIGINT NOT NULL,
    option_margin_factor_ppm BIGINT NOT NULL DEFAULT 1000000,
    PRIMARY KEY (product_line, instrument_id, bracket_no),
    CONSTRAINT instrument_risk_brackets_instrument_fk
        FOREIGN KEY (product_line, instrument_id) REFERENCES instruments(product_line, instrument_id),
    CONSTRAINT instrument_risk_brackets_positive CHECK (
        bracket_no > 0
        AND notional_floor_units >= 0
        AND notional_cap_units > notional_floor_units
        AND max_leverage_ppm > 0
        AND initial_margin_rate_ppm > 0
        AND maintenance_margin_rate_ppm > 0
        AND option_margin_factor_ppm BETWEEN 1 AND 10000000
    )
);

CREATE TABLE IF NOT EXISTS instrument_index_sources (
    instrument_id               INTEGER NOT NULL,
    product_line                     TEXT NOT NULL,
    source                      TEXT NOT NULL,
    enabled                     BOOLEAN NOT NULL,
    base_url                    TEXT NOT NULL,
    path                        TEXT NOT NULL,
    source_symbol               TEXT NOT NULL,
    parser                      TEXT NOT NULL,
    quote_currency              TEXT NOT NULL DEFAULT 'USDT',
    target_quote_currency       TEXT NOT NULL DEFAULT 'USDT',
    conversion_base_url         TEXT,
    conversion_path             TEXT,
    conversion_parser           TEXT,
    conversion_mode             TEXT NOT NULL DEFAULT 'DISCOUNT',
    conversion_operation        TEXT NOT NULL DEFAULT 'MULTIPLY',
    fallback_weight_multiplier_ppm BIGINT NOT NULL DEFAULT 500000,
    websocket_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    websocket_url               TEXT,
    websocket_subscribe_message TEXT,
    websocket_parser            TEXT,
    weight_ppm                  BIGINT NOT NULL,
    PRIMARY KEY (product_line, instrument_id, source),
    CONSTRAINT instrument_index_sources_instrument_fk
        FOREIGN KEY (product_line, instrument_id) REFERENCES instruments(product_line, instrument_id),
    CONSTRAINT instrument_index_sources_positive_weight CHECK (
        weight_ppm > 0 AND fallback_weight_multiplier_ppm >= 0
    ),
    CONSTRAINT instrument_index_sources_conversion_mode CHECK (conversion_mode IN ('DISCOUNT', 'DISABLE')),
    CONSTRAINT instrument_index_sources_conversion_operation CHECK (conversion_operation IN ('MULTIPLY', 'DIVIDE'))
);

CREATE INDEX IF NOT EXISTS instrument_index_sources_enabled_idx
    ON instrument_index_sources (product_line, instrument_id, enabled);

CREATE SEQUENCE IF NOT EXISTS instrument_outbox_event_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;

CREATE TABLE IF NOT EXISTS instrument_outbox_events (
    id                  BIGINT PRIMARY KEY,
    aggregate_type      TEXT NOT NULL,
    aggregate_id        BIGINT NOT NULL,
    topic               TEXT NOT NULL,
    event_key           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    published_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT instrument_outbox_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS instrument_outbox_pending_stream_idx
    ON instrument_outbox_events (topic, event_key, id)
    INCLUDE (next_attempt_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS instrument_outbox_published_cleanup_idx
    ON instrument_outbox_events (published_at, id)
    WHERE published_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS instrument_lifecycle_drain_acks (
    instrument_id              INTEGER NOT NULL,
    instrument_change_id  BIGINT NOT NULL,
    product_line        TEXT NOT NULL,
    component           TEXT NOT NULL,
    ready_at            TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, instrument_id, instrument_change_id, component),
    CONSTRAINT instrument_lifecycle_drain_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT instrument_lifecycle_drain_component_check CHECK (
        component IN ('ORDER', 'ACCOUNT')
    )
);

CREATE INDEX IF NOT EXISTS instrument_lifecycle_drain_ready_idx
    ON instrument_lifecycle_drain_acks (product_line, instrument_id, instrument_change_id);

-- OKX live catalog snapshot fetched 2026-08-20 UTC from the official public API.
-- Endpoints: /api/v5/public/instruments?instType=SPOT|SWAP|FUTURES and
-- /api/v5/public/underlying?instType=OPTION followed by
-- /api/v5/public/instruments?instType=OPTION&uly=<underlying>.
-- Only state=live instruments are seeded. Instruments whose assets violate this
-- project's account asset identifier constraint are skipped. OKX USDⓈ-margined
-- options are normalized to USDT settlement for the project's stablecoin path.
-- Public regular-tier fee defaults: spot 800/1000 ppm, futures/perpetual 200/500 ppm,
-- options 200/300 ppm. Delivery settlement fee is 100 ppm; option exercise fee is
-- governed by the same OKX tier rules and is not represented as an instrument column.
-- Only the three enabled test maker contracts are seeded: BTC / ETH / SOL USDT perpetuals.
-- Their permanent IDs match the existing test environment; other products start without instruments.
CREATE TEMP TABLE surprising_okx_instruments (
    product_line TEXT NOT NULL, symbol TEXT NOT NULL, instrument_type TEXT NOT NULL, contract_type TEXT NOT NULL,
    base_asset TEXT NOT NULL, quote_asset TEXT NOT NULL, settle_asset TEXT NOT NULL, contract_multiplier_ppm BIGINT NOT NULL,
    contract_value_asset TEXT NOT NULL, price_tick_units BIGINT NOT NULL, quantity_step_units BIGINT NOT NULL,
    min_quantity_steps BIGINT NOT NULL, max_quantity_steps BIGINT NOT NULL, price_precision INTEGER NOT NULL, quantity_precision INTEGER NOT NULL,
    notional_multiplier_units BIGINT NOT NULL, max_leverage_ppm BIGINT NOT NULL, initial_margin_rate_ppm BIGINT NOT NULL,
    maintenance_margin_rate_ppm BIGINT NOT NULL, funding_interval_hours INTEGER NOT NULL, expiry_ms BIGINT, underlying_symbol TEXT,
    strike_price_units BIGINT, option_type TEXT, index_symbol TEXT NOT NULL, spot_index BOOLEAN NOT NULL, effective_ms BIGINT,
    PRIMARY KEY (product_line, symbol)
) ON COMMIT DROP;

INSERT INTO surprising_okx_instruments
SELECT * FROM jsonb_to_recordset($okx$[
  {
    "product_line": "LINEAR_PERPETUAL",
    "symbol": "BTC-USDT",
    "instrument_type": "PERPETUAL",
    "contract_type": "LINEAR_PERPETUAL",
    "base_asset": "BTC",
    "quote_asset": "USDT",
    "settle_asset": "USDT",
    "contract_multiplier_ppm": "1000000",
    "contract_value_asset": "BTC",
    "price_tick_units": "100000000000",
    "quantity_step_units": "1",
    "min_quantity_steps": "1",
    "max_quantity_steps": "10000000000",
    "price_precision": 1,
    "quantity_precision": 2,
    "notional_multiplier_units": "10000000",
    "max_leverage_ppm": "100000000",
    "initial_margin_rate_ppm": "10000",
    "maintenance_margin_rate_ppm": "5000",
    "funding_interval_hours": 8,
    "expiry_ms": null,
    "underlying_symbol": null,
    "strike_price_units": null,
    "option_type": null,
    "index_symbol": "BTC-USDT",
    "spot_index": false,
    "effective_ms": "1573557408000"
  },
  {
    "product_line": "LINEAR_PERPETUAL",
    "symbol": "ETH-USDT",
    "instrument_type": "PERPETUAL",
    "contract_type": "LINEAR_PERPETUAL",
    "base_asset": "ETH",
    "quote_asset": "USDT",
    "settle_asset": "USDT",
    "contract_multiplier_ppm": "1000000",
    "contract_value_asset": "ETH",
    "price_tick_units": "10000000000",
    "quantity_step_units": "1",
    "min_quantity_steps": "1",
    "max_quantity_steps": "10000000000",
    "price_precision": 2,
    "quantity_precision": 2,
    "notional_multiplier_units": "10000000",
    "max_leverage_ppm": "100000000",
    "initial_margin_rate_ppm": "10000",
    "maintenance_margin_rate_ppm": "5000",
    "funding_interval_hours": 8,
    "expiry_ms": null,
    "underlying_symbol": null,
    "strike_price_units": null,
    "option_type": null,
    "index_symbol": "ETH-USDT",
    "spot_index": false,
    "effective_ms": "1573557408000"
  },
  {
    "product_line": "LINEAR_PERPETUAL",
    "symbol": "SOL-USDT",
    "instrument_type": "PERPETUAL",
    "contract_type": "LINEAR_PERPETUAL",
    "base_asset": "SOL",
    "quote_asset": "USDT",
    "settle_asset": "USDT",
    "contract_multiplier_ppm": "1000000",
    "contract_value_asset": "SOL",
    "price_tick_units": "10000000000",
    "quantity_step_units": "1",
    "min_quantity_steps": "1",
    "max_quantity_steps": "10000000000",
    "price_precision": 2,
    "quantity_precision": 2,
    "notional_multiplier_units": "100000000",
    "max_leverage_ppm": "100000000",
    "initial_margin_rate_ppm": "10000",
    "maintenance_margin_rate_ppm": "5000",
    "funding_interval_hours": 8,
    "expiry_ms": null,
    "underlying_symbol": null,
    "strike_price_units": null,
    "option_type": null,
    "index_symbol": "SOL-USDT",
    "spot_index": false,
    "effective_ms": "1611298800000"
  }
]$okx$::jsonb) AS rows(
    product_line TEXT, symbol TEXT, instrument_type TEXT, contract_type TEXT, base_asset TEXT, quote_asset TEXT, settle_asset TEXT,
    contract_multiplier_ppm BIGINT, contract_value_asset TEXT, price_tick_units BIGINT, quantity_step_units BIGINT,
    min_quantity_steps BIGINT, max_quantity_steps BIGINT, price_precision INTEGER, quantity_precision INTEGER,
    notional_multiplier_units BIGINT, max_leverage_ppm BIGINT, initial_margin_rate_ppm BIGINT, maintenance_margin_rate_ppm BIGINT,
    funding_interval_hours INTEGER, expiry_ms BIGINT, underlying_symbol TEXT, strike_price_units BIGINT, option_type TEXT,
    index_symbol TEXT, spot_index BOOLEAN, effective_ms BIGINT
);

ALTER TABLE surprising_okx_instruments ADD COLUMN instrument_id INTEGER;
UPDATE surprising_okx_instruments SET instrument_id = CASE symbol
    WHEN 'BTC-USDT' THEN 604 WHEN 'ETH-USDT' THEN 653 WHEN 'SOL-USDT' THEN 866 END;
SELECT setval('instrument_identity_sequence', 866, TRUE);

UPDATE surprising_okx_instruments
   SET price_tick_units = 10000000
 WHERE product_line = 'LINEAR_PERPETUAL'
   AND symbol = 'BTC-USDT';


INSERT INTO instruments (
    instrument_id, symbol, change_id, last_change_id, instrument_type, contract_type, base_asset_id, quote_asset_id, settle_asset_id, contract_multiplier_ppm, contract_value_asset_id,
    price_tick_units, quantity_step_units, min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units, notional_multiplier_units,
    price_precision, quantity_precision, supported_order_types, supported_time_in_force, post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm, maker_fee_rate_ppm, taker_fee_rate_ppm, max_position_notional_units,
    user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units, funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources, expiry_time, delivery_time, underlying_instrument_id, underlying_product_line, strike_price_units, option_type, option_exercise_style, settlement_method,
    status, effective_time, created_at, updated_at
)
SELECT seed.instrument_id, symbol, nextval('instrument_change_log_sequence'), currval('instrument_change_log_sequence'), instrument_type, contract_type, (SELECT asset_id FROM assets WHERE asset=seed.base_asset), (SELECT asset_id FROM assets WHERE asset=seed.quote_asset), (SELECT asset_id FROM assets WHERE asset=seed.settle_asset), contract_multiplier_ppm, (SELECT asset_id FROM assets WHERE asset=seed.contract_value_asset), price_tick_units, quantity_step_units,
       min_quantity_steps, max_quantity_steps, 1, 1000000000000000000, notional_multiplier_units, price_precision, quantity_precision,
       CASE WHEN instrument_type IN ('SPOT', 'PERPETUAL') THEN 'LIMIT,MARKET' ELSE 'LIMIT' END, 'GTC,IOC,FOK,GTX', TRUE, instrument_type <> 'SPOT',
       instrument_type IN ('SPOT', 'PERPETUAL'), max_leverage_ppm,
       CASE WHEN instrument_type = 'OPTION' THEN 100000 ELSE initial_margin_rate_ppm END,
       CASE WHEN instrument_type = 'OPTION' THEN 50000 ELSE maintenance_margin_rate_ppm END,
       CASE WHEN instrument_type = 'SPOT' THEN 800 WHEN instrument_type = 'OPTION' THEN 200 ELSE 200 END,
       CASE WHEN instrument_type = 'SPOT' THEN 1000 WHEN instrument_type = 'OPTION' THEN 300 ELSE 500 END,
       1000000000000000000, 1000000, 25000000000000, funding_interval_hours, CASE WHEN instrument_type = 'PERPETUAL' THEN 100 ELSE 0 END,
       CASE WHEN instrument_type = 'PERPETUAL' THEN 3000 ELSE 0 END, CASE WHEN instrument_type = 'PERPETUAL' THEN -3000 ELSE 0 END,
       1000000000000, 1, CASE WHEN expiry_ms IS NULL THEN NULL ELSE to_timestamp(expiry_ms / 1000.0) END,
       CASE WHEN expiry_ms IS NULL THEN NULL ELSE to_timestamp(expiry_ms / 1000.0) END, (SELECT u.instrument_id FROM surprising_okx_instruments u WHERE u.product_line='SPOT' AND u.symbol=seed.underlying_symbol), CASE WHEN underlying_symbol IS NOT NULL THEN 'SPOT' END, strike_price_units, option_type,
       CASE WHEN instrument_type = 'OPTION' THEN 'EUROPEAN' END, CASE WHEN instrument_type IN ('DELIVERY', 'OPTION') THEN 'CASH' END,
       'TRADING', CASE WHEN effective_ms IS NULL THEN now() ELSE to_timestamp(effective_ms / 1000.0) END, now(), now()
  FROM surprising_okx_instruments seed
ON CONFLICT (contract_type, symbol) DO UPDATE SET
    instrument_type=EXCLUDED.instrument_type, contract_type=EXCLUDED.contract_type, base_asset_id=EXCLUDED.base_asset_id, quote_asset_id=EXCLUDED.quote_asset_id, settle_asset_id=EXCLUDED.settle_asset_id,
    contract_multiplier_ppm=EXCLUDED.contract_multiplier_ppm, contract_value_asset_id=EXCLUDED.contract_value_asset_id, price_tick_units=EXCLUDED.price_tick_units, quantity_step_units=EXCLUDED.quantity_step_units,
    min_quantity_steps=EXCLUDED.min_quantity_steps, max_quantity_steps=EXCLUDED.max_quantity_steps, min_notional_units=EXCLUDED.min_notional_units, max_notional_units=EXCLUDED.max_notional_units, notional_multiplier_units=EXCLUDED.notional_multiplier_units,
    price_precision=EXCLUDED.price_precision, quantity_precision=EXCLUDED.quantity_precision, supported_order_types=EXCLUDED.supported_order_types, supported_time_in_force=EXCLUDED.supported_time_in_force,
    post_only_enabled=EXCLUDED.post_only_enabled, reduce_only_enabled=EXCLUDED.reduce_only_enabled, market_order_enabled=EXCLUDED.market_order_enabled, max_leverage_ppm=EXCLUDED.max_leverage_ppm, initial_margin_rate_ppm=EXCLUDED.initial_margin_rate_ppm, maintenance_margin_rate_ppm=EXCLUDED.maintenance_margin_rate_ppm,
    maker_fee_rate_ppm=EXCLUDED.maker_fee_rate_ppm, taker_fee_rate_ppm=EXCLUDED.taker_fee_rate_ppm, max_position_notional_units=EXCLUDED.max_position_notional_units, user_open_interest_limit_rate_ppm=EXCLUDED.user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units=EXCLUDED.user_open_interest_limit_floor_units,
    funding_interval_hours=EXCLUDED.funding_interval_hours, interest_rate_ppm=EXCLUDED.interest_rate_ppm, funding_rate_cap_ppm=EXCLUDED.funding_rate_cap_ppm, funding_rate_floor_ppm=EXCLUDED.funding_rate_floor_ppm, impact_notional_units=EXCLUDED.impact_notional_units, min_valid_index_sources=EXCLUDED.min_valid_index_sources,
    expiry_time=EXCLUDED.expiry_time, delivery_time=EXCLUDED.delivery_time, underlying_instrument_id=EXCLUDED.underlying_instrument_id, underlying_product_line=EXCLUDED.underlying_product_line, strike_price_units=EXCLUDED.strike_price_units, option_type=EXCLUDED.option_type, option_exercise_style=EXCLUDED.option_exercise_style, settlement_method=EXCLUDED.settlement_method, status=EXCLUDED.status, effective_time=EXCLUDED.effective_time, updated_at=now();


INSERT INTO instrument_risk_brackets (instrument_id, product_line, bracket_no, notional_floor_units, notional_cap_units, max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm, option_margin_factor_ppm)
SELECT instrument_id, product_line, b.bracket_no, b.floor_units, b.cap_units, GREATEST(1000000::BIGINT, max_leverage_ppm / b.divisor),
       CASE WHEN product_line = 'OPTION' THEN 150000 ELSE GREATEST(1::BIGINT, LEAST(5000::BIGINT, CEIL(1000000000000.0 / GREATEST(1000000::BIGINT, max_leverage_ppm / b.divisor)))) END,
       CASE WHEN product_line = 'OPTION' THEN 50000 ELSE GREATEST(1::BIGINT, LEAST(5000::BIGINT, CEIL(1000000000000.0 / GREATEST(1000000::BIGINT, max_leverage_ppm / b.divisor)) / 2)) END,
       b.option_margin_factor_ppm
  FROM surprising_okx_instruments CROSS JOIN (VALUES (1,0::BIGINT,100000000000000::BIGINT,1::BIGINT,1000000::BIGINT),(2,100000000000000::BIGINT,500000000000000::BIGINT,2::BIGINT,1100000::BIGINT),(3,500000000000000::BIGINT,1000000000000000000,5::BIGINT,1200000::BIGINT)) b(bracket_no,floor_units,cap_units,divisor,option_margin_factor_ppm)
ON CONFLICT (product_line, instrument_id, bracket_no) DO UPDATE SET notional_floor_units=EXCLUDED.notional_floor_units, notional_cap_units=EXCLUDED.notional_cap_units, max_leverage_ppm=EXCLUDED.max_leverage_ppm, initial_margin_rate_ppm=EXCLUDED.initial_margin_rate_ppm, maintenance_margin_rate_ppm=EXCLUDED.maintenance_margin_rate_ppm, option_margin_factor_ppm=EXCLUDED.option_margin_factor_ppm;

INSERT INTO instrument_index_sources (instrument_id, product_line, source, enabled, base_url, path, source_symbol, parser, quote_currency, target_quote_currency, conversion_base_url, conversion_path, conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm, websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm)
SELECT instrument_id, product_line, 'OKX', TRUE, 'https://www.okx.com', CASE WHEN spot_index THEN '/api/v5/market/ticker?instId='||index_symbol ELSE '/api/v5/market/index-tickers?instId='||index_symbol END, index_symbol, CASE WHEN spot_index THEN 'OKX_TICKER' ELSE 'OKX_INDEX_TICKER' END, quote_asset, quote_asset, NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000, TRUE, 'wss://ws.okx.com:8443/ws/v5/public', CASE WHEN spot_index THEN '{"op":"subscribe","args":[{"channel":"tickers","instId":"'||index_symbol||'"}]}' ELSE '{"op":"subscribe","args":[{"channel":"index-tickers","instId":"'||index_symbol||'"}]}' END, CASE WHEN spot_index THEN 'OKX_TICKER' ELSE 'OKX_INDEX_TICKER' END, 1000000
  FROM surprising_okx_instruments
ON CONFLICT (product_line, instrument_id, source) DO UPDATE SET enabled=EXCLUDED.enabled, base_url=EXCLUDED.base_url, path=EXCLUDED.path, source_symbol=EXCLUDED.source_symbol, parser=EXCLUDED.parser, quote_currency=EXCLUDED.quote_currency, target_quote_currency=EXCLUDED.target_quote_currency, websocket_subscribe_message=EXCLUDED.websocket_subscribe_message, websocket_parser=EXCLUDED.websocket_parser;

-- BTC-USDT three-source public WebSocket matrix
UPDATE instruments SET min_valid_index_sources = 3
 WHERE contract_type = 'LINEAR_PERPETUAL' AND symbol = 'BTC-USDT';

INSERT INTO instrument_index_sources (
    instrument_id, product_line, source, enabled, base_url, path, source_symbol, parser,
    quote_currency, target_quote_currency, conversion_base_url, conversion_path,
    conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm,
    websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm
) VALUES
((SELECT instrument_id FROM instruments WHERE product_line='LINEAR_PERPETUAL' AND symbol='BTC-USDT'), 'LINEAR_PERPETUAL', 'OKX', TRUE, 'https://www.okx.com', '/api/v5/market/index-tickers?instId=BTC-USDT', 'BTC-USDT', 'OKX_INDEX_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://ws.okx.com:8443/ws/v5/public', '{"op":"subscribe","args":[{"channel":"index-tickers","instId":"BTC-USDT"}]}', 'OKX_INDEX_TICKER', 1000000),
((SELECT instrument_id FROM instruments WHERE product_line='LINEAR_PERPETUAL' AND symbol='BTC-USDT'), 'LINEAR_PERPETUAL', 'BINANCE', TRUE, 'https://api.binance.com', '/api/v3/ticker/bookTicker?symbol=BTCUSDT', 'BTCUSDT', 'BINANCE_BOOK_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.binance.com:443/ws', '{"method":"SUBSCRIBE","params":["btcusdt@ticker"],"id":1}', 'BINANCE_BOOK_TICKER', 1000000),
((SELECT instrument_id FROM instruments WHERE product_line='LINEAR_PERPETUAL' AND symbol='BTC-USDT'), 'LINEAR_PERPETUAL', 'BYBIT', TRUE, 'https://api.bybit.com', '/v5/market/tickers?category=spot&symbol=BTCUSDT', 'BTCUSDT', 'BYBIT_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.bybit.com/v5/public/spot', '{"op":"subscribe","args":["tickers.BTCUSDT"]}', 'BYBIT_TICKER', 1000000)
ON CONFLICT (product_line, instrument_id, source) DO UPDATE SET
    enabled=EXCLUDED.enabled, base_url=EXCLUDED.base_url, path=EXCLUDED.path,
    source_symbol=EXCLUDED.source_symbol, parser=EXCLUDED.parser,
    quote_currency=EXCLUDED.quote_currency, target_quote_currency=EXCLUDED.target_quote_currency,
    conversion_base_url=EXCLUDED.conversion_base_url, conversion_path=EXCLUDED.conversion_path,
    conversion_parser=EXCLUDED.conversion_parser, conversion_mode=EXCLUDED.conversion_mode,
    conversion_operation=EXCLUDED.conversion_operation,
    fallback_weight_multiplier_ppm=EXCLUDED.fallback_weight_multiplier_ppm,
    websocket_enabled=EXCLUDED.websocket_enabled, websocket_url=EXCLUDED.websocket_url,
    websocket_subscribe_message=EXCLUDED.websocket_subscribe_message,
    websocket_parser=EXCLUDED.websocket_parser, weight_ppm=EXCLUDED.weight_ppm;

-- Retained seed: LINEAR_PERPETUAL BTC-USDT (604), ETH-USDT (653), SOL-USDT (866).


INSERT INTO instrument_change_log(product_line,instrument_id,symbol,change_id,operator_id,reason,changed_at,before_values,after_values)
SELECT i.product_line,i.instrument_id,i.symbol,i.change_id,'SYSTEM:INITIALIZATION','Initial configuration',i.updated_at,NULL,
    (to_jsonb(i)-'change_id'-'last_change_id') || jsonb_build_object(
        'instrumentId',i.instrument_id,
        'baseScaleUnits',(SELECT scale_units FROM assets WHERE asset_id=i.base_asset_id),
        'quoteScaleUnits',(SELECT scale_units FROM assets WHERE asset_id=i.quote_asset_id),
        'priceTickUnits',i.price_tick_units,'quantityStepUnits',i.quantity_step_units,
        'baseAsset',(SELECT asset FROM assets WHERE asset_id=i.base_asset_id),'quoteAsset',(SELECT asset FROM assets WHERE asset_id=i.quote_asset_id),
        'riskLimitBrackets',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY bracket_no) FROM instrument_risk_brackets r WHERE r.product_line=i.product_line AND r.instrument_id=i.instrument_id),'[]'::jsonb),
        'indexSources',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY source) FROM instrument_index_sources r WHERE r.product_line=i.product_line AND r.instrument_id=i.instrument_id),'[]'::jsonb))
FROM instruments i;


SET CONSTRAINTS ALL IMMEDIATE;

-- 02. Public market data, candles, index and mark-price history.

CREATE TABLE IF NOT EXISTS candlestick_candles (
    instrument_id              TEXT NOT NULL,
    period              TEXT NOT NULL,
    open_time           TIMESTAMPTZ NOT NULL,
    close_time          TIMESTAMPTZ NOT NULL,
    open_price          NUMERIC(38, 18) NOT NULL,
    high_price          NUMERIC(38, 18) NOT NULL,
    low_price           NUMERIC(38, 18) NOT NULL,
    close_price         NUMERIC(38, 18) NOT NULL,
    base_volume         NUMERIC(38, 18) NOT NULL,
    quote_volume        NUMERIC(38, 18) NOT NULL,
    trade_count         BIGINT NOT NULL,
    first_trade_id      TEXT,
    last_trade_id       TEXT,
    first_sequence      BIGINT,
    last_sequence       BIGINT,
    status              TEXT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    source_partition    INTEGER,
    source_offset       BIGINT,
    PRIMARY KEY (instrument_id, period, open_time),
    CONSTRAINT candlestick_candles_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT candlestick_candles_period_format CHECK (period ~ '^[0-9]+[mhdw]$'),
    CONSTRAINT candlestick_candles_positive_prices CHECK (
        open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0
    ),
    CONSTRAINT candlestick_candles_non_negative_volume CHECK (
        base_volume >= 0 AND quote_volume >= 0 AND trade_count >= 0
    ),
    CONSTRAINT candlestick_candles_valid_ohlc CHECK (
        high_price >= open_price AND high_price >= close_price AND high_price >= low_price
        AND low_price <= open_price AND low_price <= close_price AND low_price <= high_price
    ),
    CONSTRAINT candlestick_candles_status CHECK (status IN ('PARTIAL', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS candlestick_candles_query_desc_idx
    ON candlestick_candles (instrument_id, period, open_time DESC);

CREATE INDEX IF NOT EXISTS candlestick_candles_updated_idx
    ON candlestick_candles (updated_at DESC);

CREATE TABLE IF NOT EXISTS price_index_ticks (
    instrument_id                  TEXT NOT NULL,
    sequence                BIGINT NOT NULL,
    index_price             NUMERIC(38, 18),
    status                  TEXT NOT NULL,
    component_count         INTEGER NOT NULL,
    valid_component_count   INTEGER NOT NULL,
    total_configured_weight NUMERIC(38, 18) NOT NULL,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, sequence),
    CONSTRAINT price_index_ticks_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT price_index_ticks_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'STALE', 'INSUFFICIENT_SOURCES', 'CLAMPED'))
);

CREATE INDEX IF NOT EXISTS price_index_ticks_query_idx
    ON price_index_ticks (instrument_id, event_time DESC);

CREATE INDEX IF NOT EXISTS price_index_ticks_event_time_brin
    ON price_index_ticks USING BRIN (event_time);

CREATE TABLE IF NOT EXISTS price_index_components (
    instrument_id              TEXT NOT NULL,
    sequence            BIGINT NOT NULL,
    source              TEXT NOT NULL,
    source_symbol       TEXT NOT NULL,
    price               NUMERIC(38, 18),
    bid_price           NUMERIC(38, 18),
    ask_price           NUMERIC(38, 18),
    configured_weight   NUMERIC(38, 18) NOT NULL,
    effective_weight    NUMERIC(38, 18) NOT NULL,
    status              TEXT NOT NULL,
    reason              TEXT,
    source_time         TIMESTAMPTZ,
    received_at         TIMESTAMPTZ,
    latency_millis      BIGINT,
    PRIMARY KEY (instrument_id, sequence, source),
    CONSTRAINT price_index_components_status CHECK (status IN ('HEALTHY', 'DISABLED', 'STALE', 'OUTLIER', 'ERROR'))
);

CREATE INDEX IF NOT EXISTS price_index_components_query_idx
    ON price_index_components (instrument_id, sequence);

CREATE TABLE IF NOT EXISTS price_symbol_leases (
    module              TEXT NOT NULL,
    instrument_id              TEXT NOT NULL,
    owner_id            TEXT NOT NULL,
    lease_until         TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (module, instrument_id),
    CONSTRAINT price_symbol_leases_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647)
);

CREATE INDEX IF NOT EXISTS price_symbol_leases_expiry_idx
    ON price_symbol_leases (module, lease_until);

CREATE TABLE IF NOT EXISTS price_symbol_sequences (
    module              TEXT NOT NULL,
    instrument_id              TEXT NOT NULL,
    sequence            BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (module, instrument_id),
    CONSTRAINT price_symbol_sequences_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT price_symbol_sequences_positive CHECK (sequence > 0)
);

CREATE TABLE IF NOT EXISTS price_exchange_rates (
    base_currency       TEXT NOT NULL,
    quote_currency      TEXT NOT NULL,
    rate                NUMERIC(38, 18) NOT NULL,
    provider            TEXT NOT NULL,
    rate_time           TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (base_currency, quote_currency),
    CONSTRAINT price_exchange_rates_currency_format CHECK (
        base_currency ~ '^[A-Z]{2,10}$' AND quote_currency ~ '^[A-Z]{2,10}$'
    ),
    CONSTRAINT price_exchange_rates_positive_rate CHECK (rate > 0)
);

CREATE INDEX IF NOT EXISTS price_exchange_rates_base_idx
    ON price_exchange_rates (base_currency, quote_currency);

CREATE INDEX IF NOT EXISTS price_exchange_rates_updated_idx
    ON price_exchange_rates (updated_at DESC);

CREATE TABLE IF NOT EXISTS price_mark_ticks (
    product_line                TEXT NOT NULL,
    instrument_id                      TEXT NOT NULL,
    instrument_change_id          BIGINT NOT NULL,
    sequence                    BIGINT NOT NULL,
    mark_price                  NUMERIC(38, 18) NOT NULL,
    mark_price_units            BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    index_price                 NUMERIC(38, 18) NOT NULL,
    price1                      NUMERIC(38, 18) NOT NULL,
    price2                      NUMERIC(38, 18),
    last_trade_price            NUMERIC(38, 18),
    best_bid_price              NUMERIC(38, 18),
    best_ask_price              NUMERIC(38, 18),
    funding_rate                NUMERIC(38, 18) NOT NULL,
    next_funding_time           TIMESTAMPTZ,
    time_until_funding_seconds  BIGINT NOT NULL,
    basis_average               NUMERIC(38, 18),
    basis_window_seconds        BIGINT NOT NULL,
    clamp_low                   NUMERIC(38, 18) NOT NULL,
    clamp_high                  NUMERIC(38, 18) NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    published_at                TIMESTAMPTZ NOT NULL,
    calculation_inputs          JSONB NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, sequence),
    CONSTRAINT price_mark_ticks_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT price_mark_ticks_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'STALE', 'INSUFFICIENT_SOURCES', 'CLAMPED')),
    CONSTRAINT price_mark_ticks_positive_units CHECK (mark_price_units > 0),
    CONSTRAINT price_mark_ticks_positive_ticks CHECK (mark_price_ticks > 0),
    CONSTRAINT price_mark_ticks_complete_inputs CHECK (
        status = 'DEGRADED' OR (price2 IS NOT NULL AND last_trade_price IS NOT NULL
        AND best_bid_price IS NOT NULL AND best_ask_price IS NOT NULL AND basis_average IS NOT NULL)
    ),
    CONSTRAINT price_mark_ticks_valid_book CHECK (best_bid_price <= best_ask_price),
    CONSTRAINT price_mark_ticks_valid_clamp CHECK (clamp_low <= mark_price AND mark_price <= clamp_high)
);

CREATE INDEX IF NOT EXISTS price_mark_ticks_query_idx
    ON price_mark_ticks (instrument_id, event_time DESC);

CREATE INDEX IF NOT EXISTS price_mark_ticks_retention_idx
    ON price_mark_ticks USING BRIN (event_time) WITH (pages_per_range = 64);

-- 03. Funding-rate history.

CREATE TABLE IF NOT EXISTS funding_rate_ticks (
    instrument_id                  TEXT NOT NULL,
    sequence                BIGINT NOT NULL,
    funding_time            TIMESTAMPTZ NOT NULL,
    funding_interval_hours  INTEGER NOT NULL,
    premium_rate_ppm        BIGINT NOT NULL,
    interest_rate_ppm       BIGINT NOT NULL,
    funding_rate_ppm        BIGINT NOT NULL,
    status                  TEXT NOT NULL,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, sequence),
    CONSTRAINT funding_rate_ticks_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT funding_rate_ticks_interval_positive CHECK (funding_interval_hours > 0),
    CONSTRAINT funding_rate_ticks_status_check CHECK (status IN ('PREDICTED', 'FINAL'))
);

CREATE INDEX IF NOT EXISTS funding_rate_ticks_symbol_time_idx
    ON funding_rate_ticks (instrument_id, funding_time DESC, sequence DESC);

-- 04. Trading configuration.
CREATE SEQUENCE IF NOT EXISTS trading_fee_schedule_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;

CREATE TABLE IF NOT EXISTS trading_fee_schedules (
    fee_schedule_id     BIGINT PRIMARY KEY,
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id             BIGINT NOT NULL,
    instrument_id              TEXT,
    maker_fee_rate_ppm  BIGINT NOT NULL,
    taker_fee_rate_ppm  BIGINT NOT NULL,
    source_type         TEXT NOT NULL DEFAULT 'USER_OVERRIDE',
    tier_code           TEXT,
    reason              TEXT NOT NULL,
    status              TEXT NOT NULL,
    effective_time      TIMESTAMPTZ NOT NULL,
    expire_time         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_fee_schedules_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_fee_schedules_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_fee_schedules_symbol_format CHECK (
        instrument_id IS NULL OR instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647
    ),
    CONSTRAINT trading_fee_schedules_source_type_check CHECK (
        source_type IN ('USER_OVERRIDE', 'VIP', 'MARKET_MAKER', 'PROMOTION', 'RISK_OVERRIDE')
    ),
    CONSTRAINT trading_fee_schedules_tier_code_format CHECK (
        tier_code IS NULL OR tier_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'
    ),
    CONSTRAINT trading_fee_schedules_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT trading_fee_schedules_fee_range CHECK (
        maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT trading_fee_schedules_time_check CHECK (
        expire_time IS NULL OR expire_time > effective_time
    )
);

DO $$
BEGIN
    ALTER TABLE trading_fee_schedules
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_fee_schedules
        DROP CONSTRAINT IF EXISTS trading_fee_schedules_product_line_check;
    ALTER TABLE trading_fee_schedules
        ADD CONSTRAINT trading_fee_schedules_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS trading_fee_schedules_user_symbol_idx;
CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_symbol_idx
    ON trading_fee_schedules (product_line, user_id, instrument_id, status, effective_time DESC, fee_schedule_id DESC);

DROP INDEX IF EXISTS trading_fee_schedules_user_global_idx;
CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_global_idx
    ON trading_fee_schedules (product_line, user_id, status, effective_time DESC, fee_schedule_id DESC)
    WHERE instrument_id IS NULL;

CREATE SEQUENCE IF NOT EXISTS account_open_interest_revision_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;

CREATE TABLE IF NOT EXISTS trading_symbol_open_interest_shards (
    product_line            TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    instrument_id                  TEXT NOT NULL,
    shard_id                SMALLINT NOT NULL,
    long_quantity_steps     BIGINT NOT NULL DEFAULT 0,
    short_quantity_steps    BIGINT NOT NULL DEFAULT 0,
    cache_revision          BIGINT NOT NULL DEFAULT nextval('account_open_interest_revision_seq'),
    updated_at              TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, instrument_id, shard_id),
    CONSTRAINT trading_symbol_open_interest_shards_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_symbol_open_interest_shards_shard_check CHECK (shard_id >= 0 AND shard_id < 64),
    CONSTRAINT trading_symbol_open_interest_shards_non_negative CHECK (
        long_quantity_steps >= 0
        AND short_quantity_steps >= 0
    )
);

ALTER TABLE trading_symbol_open_interest_shards
    ADD COLUMN IF NOT EXISTS cache_revision BIGINT NOT NULL DEFAULT nextval('account_open_interest_revision_seq');

CREATE INDEX IF NOT EXISTS trading_symbol_open_interest_shards_revision_idx
    ON trading_symbol_open_interest_shards (product_line, cache_revision);

CREATE OR REPLACE VIEW trading_symbol_open_interest AS
SELECT product_line,
       instrument_id,
       COALESCE(SUM(long_quantity_steps), 0)::BIGINT AS long_quantity_steps,
       COALESCE(SUM(short_quantity_steps), 0)::BIGINT AS short_quantity_steps,
       GREATEST(
           COALESCE(SUM(long_quantity_steps), 0),
           COALESCE(SUM(short_quantity_steps), 0)
       )::BIGINT AS open_quantity_steps,
       MAX(updated_at) AS updated_at
  FROM trading_symbol_open_interest_shards
 GROUP BY product_line, instrument_id;

-- 05. Account ledger and administrative adjustments.
-- Aeron Core remains authoritative for online balances, reservations and positions.

CREATE SEQUENCE IF NOT EXISTS account_ledger_entry_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_product_ledger_entry_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_product_transfer_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;

CREATE TABLE IF NOT EXISTS account_product_ledger_entries (
    entry_id            BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    account_type        TEXT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    instrument_id              TEXT,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_product_ledger_type_check CHECK (
        account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_product_ledger_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_product_ledger_symbol_format CHECK (
        instrument_id IS NULL OR instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647
    )
);

ALTER TABLE account_product_ledger_entries
    ADD COLUMN IF NOT EXISTS instrument_id TEXT;

ALTER TABLE account_product_ledger_entries
    DROP CONSTRAINT IF EXISTS account_product_ledger_symbol_format;

ALTER TABLE account_product_ledger_entries
    ADD CONSTRAINT account_product_ledger_symbol_format CHECK (
        instrument_id IS NULL OR instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647
    );

CREATE UNIQUE INDEX IF NOT EXISTS account_product_ledger_reference_uidx
    ON account_product_ledger_entries (reference_type, reference_id, user_id, account_type, asset);

CREATE INDEX IF NOT EXISTS account_product_ledger_user_time_idx
    ON account_product_ledger_entries (user_id, account_type, created_at DESC);

CREATE TABLE IF NOT EXISTS account_product_transfers (
    transfer_id         BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    source_account_type TEXT NOT NULL,
    target_account_type TEXT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    reference_id        TEXT NOT NULL,
    status              TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_product_transfers_type_check CHECK (
        source_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        AND target_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                    'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        AND source_account_type <> target_account_type
    ),
    CONSTRAINT account_product_transfers_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_transfers_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_transfers_amount_positive CHECK (amount_units > 0),
    CONSTRAINT account_product_transfers_reference_present CHECK (length(reference_id) > 0),
    CONSTRAINT account_product_transfers_status_check CHECK (status IN ('COMPLETED'))
);

DROP INDEX IF EXISTS account_product_transfers_reference_uidx;

CREATE INDEX IF NOT EXISTS account_product_transfers_reference_idx
    ON account_product_transfers (user_id, reference_id);

CREATE INDEX IF NOT EXISTS account_product_transfers_user_time_idx
    ON account_product_transfers (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS account_ledger_entries (
    entry_id            BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    trade_id            BIGINT,
    order_id            BIGINT,
    instrument_id              TEXT,
    fee_rate_ppm        BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_ledger_user_positive CHECK (user_id > 0),
    CONSTRAINT account_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_ledger_trade_fee_metadata_check CHECK (
        reference_type NOT IN ('TRADE_FEE', 'LIQUIDATION_FEE')
        OR (trade_id IS NOT NULL AND order_id IS NOT NULL AND instrument_id IS NOT NULL AND fee_rate_ppm IS NOT NULL)
    ),
    CONSTRAINT account_ledger_fee_rate_range CHECK (
        fee_rate_ppm IS NULL OR fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT account_ledger_symbol_format CHECK (
        instrument_id IS NULL OR instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS account_ledger_reference_uidx
    ON account_ledger_entries (reference_type, reference_id, user_id, asset);

CREATE INDEX IF NOT EXISTS account_ledger_user_time_idx
    ON account_ledger_entries (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS account_ledger_trade_fee_order_idx
    ON account_ledger_entries (order_id, trade_id)
    WHERE reference_type = 'TRADE_FEE';

CREATE INDEX IF NOT EXISTS account_ledger_liquidation_fee_order_idx
    ON account_ledger_entries (order_id, trade_id)
    WHERE reference_type = 'LIQUIDATION_FEE';

CREATE TABLE IF NOT EXISTS account_admin_balance_adjustments (
    adjustment_id       BIGSERIAL PRIMARY KEY,
    reference_key       TEXT NOT NULL,
    adjustment_kind     TEXT NOT NULL,
    admin_user_id       BIGINT NOT NULL,
    admin_username      TEXT,
    user_id             BIGINT NOT NULL,
    account_type        TEXT,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_admin_adjustments_kind_check CHECK (adjustment_kind IN ('BASIC', 'PRODUCT')),
    CONSTRAINT account_admin_adjustments_type_check CHECK (
        account_type IS NULL OR account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_admin_adjustments_kind_type_check CHECK (
        (adjustment_kind = 'BASIC' AND account_type IS NULL)
        OR (adjustment_kind = 'PRODUCT' AND account_type IS NOT NULL)
    ),
    CONSTRAINT account_admin_adjustments_admin_positive CHECK (admin_user_id > 0),
    CONSTRAINT account_admin_adjustments_user_positive CHECK (user_id > 0),
    CONSTRAINT account_admin_adjustments_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_admin_adjustments_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_admin_adjustments_reference_present CHECK (length(reference_id) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS account_admin_adjustments_reference_uidx
    ON account_admin_balance_adjustments (reference_key);

CREATE INDEX IF NOT EXISTS account_admin_adjustments_admin_time_idx
    ON account_admin_balance_adjustments (admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS account_admin_adjustments_user_time_idx
    ON account_admin_balance_adjustments (user_id, created_at DESC);

DROP TABLE IF EXISTS account_margin_reservations;
DROP SEQUENCE IF EXISTS trading_margin_reservation_seq;

DO $$
BEGIN
    IF to_regclass('public.account_product_ledger_entries') IS NOT NULL THEN
        ALTER TABLE account_product_ledger_entries DROP CONSTRAINT IF EXISTS account_product_ledger_type_check;
        ALTER TABLE account_product_ledger_entries
            ADD CONSTRAINT account_product_ledger_type_check CHECK (
                account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

    IF to_regclass('public.account_product_transfers') IS NOT NULL THEN
        ALTER TABLE account_product_transfers DROP CONSTRAINT IF EXISTS account_product_transfers_type_check;
        ALTER TABLE account_product_transfers
            ADD CONSTRAINT account_product_transfers_type_check CHECK (
                source_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                        'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
                AND target_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                            'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
                AND source_account_type <> target_account_type
            );
    END IF;

    IF to_regclass('public.account_admin_balance_adjustments') IS NOT NULL THEN
        ALTER TABLE account_admin_balance_adjustments DROP CONSTRAINT IF EXISTS account_admin_adjustments_type_check;
        ALTER TABLE account_admin_balance_adjustments
            ADD CONSTRAINT account_admin_adjustments_type_check CHECK (
                account_type IS NULL OR account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

END $$;



-- 06. Insurance fund and ADL audit history.


CREATE TABLE IF NOT EXISTS insurance_fund_ledger (
    entry_id            BIGINT PRIMARY KEY,
    account_type        TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    instrument_id              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_fund_ledger_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT insurance_fund_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_fund_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT insurance_fund_ledger_balance_non_negative CHECK (balance_after_units >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, account_type, asset);

CREATE INDEX IF NOT EXISTS insurance_fund_ledger_asset_time_idx
    ON insurance_fund_ledger (account_type, asset, created_at DESC);

CREATE TABLE IF NOT EXISTS insurance_deficit_coverages (
    coverage_id                 BIGINT PRIMARY KEY,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    user_id                     BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    requested_units             BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    remaining_deficit_units     BIGINT NOT NULL,
    reserve_command_id          VARCHAR(160) NOT NULL,
    finalize_command_id         VARCHAR(160) NOT NULL,
    status                      TEXT NOT NULL,
    reason                      TEXT,
    error_code                  VARCHAR(80),
    error_message               VARCHAR(1000),
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_coverages_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT insurance_coverages_user_positive CHECK (user_id > 0),
    CONSTRAINT insurance_coverages_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_coverages_non_negative CHECK (
        requested_units > 0
        AND covered_units > 0
        AND remaining_deficit_units >= 0
        AND covered_units <= requested_units
    ),
    CONSTRAINT insurance_coverages_status_check CHECK (
        status IN ('PENDING_RESERVE', 'PENDING_FINALIZE', 'COVERED', 'PARTIALLY_COVERED', 'FAILED')
    ),
    CONSTRAINT insurance_coverages_completion_check CHECK (
        (status IN ('COVERED', 'PARTIALLY_COVERED', 'FAILED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING_RESERVE', 'PENDING_FINALIZE') AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_reserve_command_uidx
    ON insurance_deficit_coverages (reserve_command_id);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_finalize_command_uidx
    ON insurance_deficit_coverages (finalize_command_id);

CREATE INDEX IF NOT EXISTS insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (account_type, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS insurance_coverages_asset_time_idx
    ON insurance_deficit_coverages (account_type, asset, created_at DESC);

DO $$
BEGIN
    ALTER TABLE insurance_fund_ledger
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE insurance_fund_ledger
        DROP CONSTRAINT IF EXISTS insurance_fund_ledger_type_check;
    ALTER TABLE insurance_fund_ledger
        ADD CONSTRAINT insurance_fund_ledger_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );

    ALTER TABLE insurance_deficit_coverages
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE insurance_deficit_coverages
        DROP CONSTRAINT IF EXISTS insurance_coverages_type_check;
    ALTER TABLE insurance_deficit_coverages
        ADD CONSTRAINT insurance_coverages_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS insurance_fund_ledger_reference_uidx;
CREATE UNIQUE INDEX IF NOT EXISTS insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, account_type, asset);

DROP INDEX IF EXISTS insurance_fund_ledger_asset_time_idx;
CREATE INDEX IF NOT EXISTS insurance_fund_ledger_asset_time_idx
    ON insurance_fund_ledger (account_type, asset, created_at DESC);

DROP INDEX IF EXISTS insurance_coverages_user_time_idx;
CREATE INDEX IF NOT EXISTS insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (account_type, user_id, created_at DESC);

DROP INDEX IF EXISTS insurance_coverages_asset_time_idx;
CREATE INDEX IF NOT EXISTS insurance_coverages_asset_time_idx
    ON insurance_deficit_coverages (account_type, asset, created_at DESC);

CREATE TABLE IF NOT EXISTS adl_events (
    event_id                    BIGINT PRIMARY KEY,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    deficit_user_id             BIGINT NOT NULL,
    target_user_id              BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    instrument_id                      TEXT NOT NULL,
    target_side                 TEXT NOT NULL,
    target_position_side        TEXT NOT NULL DEFAULT 'NET',
    closed_quantity_steps       BIGINT NOT NULL,
    entry_price_ticks           BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    requested_deficit_units     BIGINT NOT NULL,
    realized_profit_units       BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    remaining_deficit_units     BIGINT NOT NULL,
    priority_score_ppm          BIGINT NOT NULL,
    reason                      TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT adl_events_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT adl_events_users_positive CHECK (deficit_user_id > 0 AND target_user_id > 0),
    CONSTRAINT adl_events_distinct_users CHECK (deficit_user_id <> target_user_id),
    CONSTRAINT adl_events_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT adl_events_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT adl_events_side_check CHECK (target_side IN ('LONG', 'SHORT')),
    CONSTRAINT adl_events_position_side_check CHECK (target_position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT adl_events_positive_values CHECK (
        closed_quantity_steps > 0
        AND entry_price_ticks > 0
        AND mark_price_ticks > 0
        AND requested_deficit_units > 0
        AND realized_profit_units > 0
        AND covered_units > 0
        AND remaining_deficit_units >= 0
        AND priority_score_ppm >= 0
    )
);

CREATE INDEX IF NOT EXISTS adl_events_deficit_user_time_idx
    ON adl_events (account_type, deficit_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_target_user_time_idx
    ON adl_events (account_type, target_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_asset_symbol_time_idx
    ON adl_events (account_type, asset, instrument_id, created_at DESC);

DO $$
BEGIN
    ALTER TABLE adl_events
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE adl_events
        DROP CONSTRAINT IF EXISTS adl_events_type_check;
    ALTER TABLE adl_events
        ADD CONSTRAINT adl_events_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS adl_events_deficit_user_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_deficit_user_time_idx
    ON adl_events (account_type, deficit_user_id, created_at DESC);

DROP INDEX IF EXISTS adl_events_target_user_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_target_user_time_idx
    ON adl_events (account_type, target_user_id, created_at DESC);

DROP INDEX IF EXISTS adl_events_asset_symbol_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_asset_symbol_time_idx
    ON adl_events (account_type, asset, instrument_id, created_at DESC);

-- 07. Market-maker operational state and audit samples.

CREATE TABLE IF NOT EXISTS market_maker_strategy_leases (
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    instrument_id                      TEXT NOT NULL,
    owner_id                    TEXT NOT NULL,
    lease_until                 TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_line, strategy_id, instrument_id),
    CONSTRAINT market_maker_leases_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_leases_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_leases_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT market_maker_leases_owner_present CHECK (length(owner_id) > 0)
);

DROP INDEX IF EXISTS market_maker_strategy_leases_until_idx;
CREATE INDEX IF NOT EXISTS market_maker_strategy_leases_until_idx
    ON market_maker_strategy_leases (product_line, lease_until ASC);

CREATE TABLE IF NOT EXISTS market_maker_business_settings (
    product_line TEXT PRIMARY KEY,
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
    version BIGINT NOT NULL CHECK (version > 0),
    updated_by TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE market_maker_business_settings IS '后台维护的产品线做市公共设置；程序初始化为关闭，仅数据库为运行时业务配置来源。';
COMMENT ON COLUMN market_maker_business_settings.product_line IS '做市业务设置所属产品线。';
COMMENT ON COLUMN market_maker_business_settings.settings IS '经参数校验的报价、库存、参考行情与策略运行设置。';
COMMENT ON COLUMN market_maker_business_settings.version IS '后台修改的乐观锁版本。';
COMMENT ON COLUMN market_maker_business_settings.updated_by IS '最近修改管理员 ID。';
COMMENT ON COLUMN market_maker_business_settings.reason IS '最近修改原因。';
COMMENT ON COLUMN market_maker_business_settings.updated_at IS '最近修改时间。';

CREATE TABLE IF NOT EXISTS market_maker_strategies (
    product_line TEXT NOT NULL,
    strategy_id TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    account_ids BIGINT[] NOT NULL CHECK (cardinality(account_ids) BETWEEN 1 AND 64),
    instrument_ids TEXT[] NOT NULL CHECK (cardinality(instrument_ids) BETWEEN 1 AND 64),
    base_quantity_steps BIGINT NOT NULL CHECK (base_quantity_steps > 0),
    margin_mode TEXT NOT NULL CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    spread_ticks BIGINT NOT NULL CHECK (spread_ticks >= 0),
    level_spacing_ticks BIGINT NOT NULL CHECK (level_spacing_ticks >= 0),
    max_inventory_steps BIGINT NOT NULL CHECK (max_inventory_steps >= 0),
    max_inventory_skew_ppm BIGINT NOT NULL CHECK (max_inventory_skew_ppm BETWEEN 0 AND 1000000),
    order_levels INTEGER NOT NULL CHECK (order_levels BETWEEN 1 AND 50),
    initial_anchor_price_ticks BIGINT NOT NULL CHECK (initial_anchor_price_ticks >= 0),
    version BIGINT NOT NULL CHECK (version > 0),
    updated_by TEXT NOT NULL,
    reason TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_line, strategy_id)
);
COMMENT ON TABLE market_maker_strategies IS '后台维护的完整做市策略定义，按产品线隔离，版本校验后热加载。';

COMMENT ON COLUMN market_maker_strategies.product_line IS '策略所属产品线，禁止跨产品线执行。';
COMMENT ON COLUMN market_maker_strategies.strategy_id IS '后台指定的永久策略标识。';
COMMENT ON COLUMN market_maker_strategies.enabled IS '管理员是否启用该策略。';
COMMENT ON COLUMN market_maker_strategies.account_ids IS '策略绑定的做市账户永久 ID 列表。';
COMMENT ON COLUMN market_maker_strategies.instrument_ids IS '策略绑定的合约永久 ID 列表。';
COMMENT ON COLUMN market_maker_strategies.base_quantity_steps IS '每档基础报价数量，单位为合约数量步。';
COMMENT ON COLUMN market_maker_strategies.margin_mode IS '策略下单保证金模式，全仓或逐仓。';
COMMENT ON COLUMN market_maker_strategies.spread_ticks IS '基础报价价差，单位为合约价格跳动。';
COMMENT ON COLUMN market_maker_strategies.level_spacing_ticks IS '相邻报价档位间距，单位为合约价格跳动。';
COMMENT ON COLUMN market_maker_strategies.max_inventory_steps IS '最大库存，单位为合约数量步。';
COMMENT ON COLUMN market_maker_strategies.max_inventory_skew_ppm IS '库存偏移比例，百万分比。';
COMMENT ON COLUMN market_maker_strategies.order_levels IS '每侧报价档位数。';
COMMENT ON COLUMN market_maker_strategies.initial_anchor_price_ticks IS '显式启动锚定价格，为零时禁用。';
COMMENT ON COLUMN market_maker_strategies.version IS '策略配置版本，用于防止并发覆盖。';
COMMENT ON COLUMN market_maker_strategies.updated_by IS '最近修改管理员 ID。';
COMMENT ON COLUMN market_maker_strategies.reason IS '最近一次修改原因。';
COMMENT ON COLUMN market_maker_strategies.updated_at IS '最近配置修改时间。';

CREATE TABLE IF NOT EXISTS market_maker_strategy_overrides (
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    enabled                     BOOLEAN,
    base_quantity_steps         BIGINT,
    margin_mode                 TEXT,
    spread_ticks                BIGINT,
    level_spacing_ticks         BIGINT,
    max_inventory_steps         BIGINT,
    max_inventory_skew_ppm      BIGINT,
    order_levels                INTEGER,
    updated_by_admin_user_id    TEXT NOT NULL,
    reason                      TEXT NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (product_line, strategy_id),
    CONSTRAINT market_maker_overrides_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_overrides_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_overrides_margin_mode CHECK (margin_mode IS NULL OR margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT market_maker_overrides_base_qty CHECK (base_quantity_steps IS NULL OR base_quantity_steps > 0),
    CONSTRAINT market_maker_overrides_spread CHECK (spread_ticks IS NULL OR spread_ticks >= 0),
    CONSTRAINT market_maker_overrides_level_spacing CHECK (level_spacing_ticks IS NULL OR level_spacing_ticks >= 0),
    CONSTRAINT market_maker_overrides_inventory CHECK (max_inventory_steps IS NULL OR max_inventory_steps > 0),
    CONSTRAINT market_maker_overrides_inventory_skew CHECK (
        max_inventory_skew_ppm IS NULL OR max_inventory_skew_ppm BETWEEN 0 AND 1000000
    ),
    CONSTRAINT market_maker_overrides_order_levels CHECK (order_levels IS NULL OR order_levels BETWEEN 1 AND 50),
    CONSTRAINT market_maker_overrides_admin_present CHECK (length(updated_by_admin_user_id) > 0),
    CONSTRAINT market_maker_overrides_reason_present CHECK (length(reason) BETWEEN 1 AND 500),
    CONSTRAINT market_maker_overrides_version_positive CHECK (version > 0)
);

DROP INDEX IF EXISTS market_maker_strategy_overrides_updated_idx;
CREATE INDEX IF NOT EXISTS market_maker_strategy_overrides_updated_idx
    ON market_maker_strategy_overrides (product_line, updated_at DESC);

CREATE TABLE IF NOT EXISTS market_maker_strategy_run_events (
    event_id                    BIGSERIAL PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    instrument_id                      TEXT,
    account_id                  BIGINT,
    node_id                     TEXT NOT NULL,
    cycle_sequence              BIGINT NOT NULL DEFAULT 0,
    event_type                  TEXT NOT NULL,
    submitted_orders            BIGINT NOT NULL DEFAULT 0,
    canceled_orders             BIGINT NOT NULL DEFAULT 0,
    rejected_orders             BIGINT NOT NULL DEFAULT 0,
    skipped_reason              TEXT,
    error_message               TEXT,
    trace_id                    TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT market_maker_run_events_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_run_events_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_run_events_symbol_format CHECK (
        instrument_id IS NULL OR instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647
    ),
    CONSTRAINT market_maker_run_events_account_positive CHECK (account_id IS NULL OR account_id > 0),
    CONSTRAINT market_maker_run_events_node_present CHECK (length(node_id) > 0),
    CONSTRAINT market_maker_run_events_cycle_non_negative CHECK (cycle_sequence >= 0),
    CONSTRAINT market_maker_run_events_type_check CHECK (
        event_type IN ('CYCLE_SUCCESS', 'CYCLE_FAILED', 'QUOTE_RECONCILED', 'TRADE_SUBMITTED', 'TRADE_EXECUTED', 'TRADE_NO_FILL', 'TRADE_REJECTED', 'SKIPPED', 'STRATEGY_DRAINING')
    ),
    CONSTRAINT market_maker_run_events_counts_non_negative CHECK (
        submitted_orders >= 0 AND canceled_orders >= 0 AND rejected_orders >= 0
    )
);

DROP INDEX IF EXISTS market_maker_run_events_strategy_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_strategy_time_idx
    ON market_maker_strategy_run_events (product_line, strategy_id, created_at DESC);

DROP INDEX IF EXISTS market_maker_run_events_symbol_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_symbol_time_idx
    ON market_maker_strategy_run_events (product_line, instrument_id, created_at DESC)
    WHERE instrument_id IS NOT NULL;

DROP INDEX IF EXISTS market_maker_run_events_account_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_account_time_idx
    ON market_maker_strategy_run_events (product_line, account_id, created_at DESC)
    WHERE account_id IS NOT NULL;

DROP INDEX IF EXISTS market_maker_run_events_trace_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_trace_idx
    ON market_maker_strategy_run_events (product_line, trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS market_maker_reference_samples (
    sample_id                   BIGSERIAL PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    instrument_id                      TEXT NOT NULL,
    node_id                     TEXT NOT NULL,
    cycle_sequence              BIGINT NOT NULL DEFAULT 0,
    source_name                 TEXT NOT NULL,
    transport                   TEXT NOT NULL,
    bid_levels                  INTEGER NOT NULL,
    ask_levels                  INTEGER NOT NULL,
    best_bid_ticks              BIGINT NOT NULL,
    best_ask_ticks              BIGINT NOT NULL,
    mid_price_ticks             BIGINT NOT NULL,
    spread_ticks                BIGINT NOT NULL,
    received_at                 TIMESTAMPTZ NOT NULL,
    trace_id                    TEXT,
    sampled_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT market_maker_reference_samples_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_reference_samples_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_reference_samples_symbol_format CHECK (instrument_id ~ '^[1-9][0-9]{0,9}$' AND instrument_id::numeric <= 2147483647),
    CONSTRAINT market_maker_reference_samples_node_present CHECK (length(node_id) > 0),
    CONSTRAINT market_maker_reference_samples_cycle_non_negative CHECK (cycle_sequence >= 0),
    CONSTRAINT market_maker_reference_samples_source_present CHECK (length(source_name) > 0),
    CONSTRAINT market_maker_reference_samples_transport_check CHECK (transport IN ('REST', 'WEBSOCKET', 'UNKNOWN')),
    CONSTRAINT market_maker_reference_samples_depth_positive CHECK (bid_levels > 0 AND ask_levels > 0),
    CONSTRAINT market_maker_reference_samples_prices_valid CHECK (
        best_bid_ticks > 0 AND best_ask_ticks > best_bid_ticks
        AND mid_price_ticks > 0 AND spread_ticks > 0
    )
);

DO $$
BEGIN
    ALTER TABLE market_maker_strategy_leases
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_leases
        DROP CONSTRAINT IF EXISTS market_maker_strategy_leases_pkey;
    ALTER TABLE market_maker_strategy_leases
        ADD CONSTRAINT market_maker_strategy_leases_pkey PRIMARY KEY (product_line, strategy_id, instrument_id);
    ALTER TABLE market_maker_strategy_leases
        DROP CONSTRAINT IF EXISTS market_maker_leases_product_line_check;
    ALTER TABLE market_maker_strategy_leases
        ADD CONSTRAINT market_maker_leases_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_strategy_overrides
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_overrides
        DROP CONSTRAINT IF EXISTS market_maker_strategy_overrides_pkey;
    ALTER TABLE market_maker_strategy_overrides
        ADD CONSTRAINT market_maker_strategy_overrides_pkey PRIMARY KEY (product_line, strategy_id);
    ALTER TABLE market_maker_strategy_overrides
        DROP CONSTRAINT IF EXISTS market_maker_overrides_product_line_check;
    ALTER TABLE market_maker_strategy_overrides
        ADD CONSTRAINT market_maker_overrides_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_strategy_run_events
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_run_events
        DROP CONSTRAINT IF EXISTS market_maker_run_events_product_line_check;
    ALTER TABLE market_maker_strategy_run_events
        ADD CONSTRAINT market_maker_run_events_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_reference_samples
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_reference_samples
        DROP CONSTRAINT IF EXISTS market_maker_reference_samples_product_line_check;
    ALTER TABLE market_maker_reference_samples
        ADD CONSTRAINT market_maker_reference_samples_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS market_maker_reference_samples_strategy_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_strategy_time_idx
    ON market_maker_reference_samples (product_line, strategy_id, sampled_at DESC);

DROP INDEX IF EXISTS market_maker_reference_samples_symbol_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_symbol_time_idx
    ON market_maker_reference_samples (product_line, instrument_id, sampled_at DESC);

DROP INDEX IF EXISTS market_maker_reference_samples_transport_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_transport_time_idx
    ON market_maker_reference_samples (product_line, transport, sampled_at DESC);

-- 08. Gateway identity, security, wallet workflow, compliance and support.

CREATE TABLE IF NOT EXISTS gateway_users (
    user_id             BIGSERIAL PRIMARY KEY,
    username            TEXT,
    email               TEXT,
    phone               TEXT,
    password_hash       TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'NORMAL',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_users_username_format CHECK (username ~ '^[a-z0-9_]{3,32}$'),
    CONSTRAINT gateway_users_email_length CHECK (email IS NULL OR length(email) <= 254),
    CONSTRAINT gateway_users_phone_format CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT gateway_users_status_check CHECK (
        status IN ('NORMAL', 'FROZEN', 'TRADE_DISABLED', 'WITHDRAW_DISABLED')
    )
);

ALTER TABLE gateway_users ALTER COLUMN username DROP NOT NULL;
ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS phone TEXT;
ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_username_uidx
    ON gateway_users (lower(username));

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_email_uidx
    ON gateway_users (lower(email))
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_phone_uidx
    ON gateway_users (phone)
    WHERE phone IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_auth_challenges (
    challenge_id        BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    purpose             TEXT NOT NULL,
    channel             TEXT NOT NULL,
    destination         TEXT NOT NULL,
    code_hash           TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    request_ip          INET,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_auth_challenges_purpose_check CHECK (
        purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'LOGIN', 'SENSITIVE_ACTION')
    ),
    CONSTRAINT gateway_auth_challenges_channel_check CHECK (channel IN ('EMAIL', 'PHONE')),
    CONSTRAINT gateway_auth_challenges_attempts_check CHECK (attempts BETWEEN 0 AND 5)
);

CREATE INDEX IF NOT EXISTS gateway_auth_challenges_active_idx
    ON gateway_auth_challenges (user_id, purpose, destination, created_at DESC)
    WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS gateway_roles (
    role_id             BIGSERIAL PRIMARY KEY,
    role_code           TEXT NOT NULL,
    role_name           TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_roles_code_format CHECK (role_code ~ '^[A-Z0-9_]{2,64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_roles_code_uidx
    ON gateway_roles (role_code);

INSERT INTO gateway_roles (role_code, role_name)
VALUES
    ('USER', 'Standard user'),
    ('SUPPORT', 'Customer support read-only operator'),
    ('ADMIN', 'Admin operator'),
    ('SUPER_ADMIN', 'Super administrator')
ON CONFLICT (role_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS gateway_user_roles (
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    role_id             BIGINT NOT NULL REFERENCES gateway_roles(role_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS gateway_user_roles_role_idx
    ON gateway_user_roles (role_id);

CREATE TABLE IF NOT EXISTS gateway_permissions (
    permission_id       BIGSERIAL PRIMARY KEY,
    permission_code     TEXT NOT NULL,
    permission_name     TEXT NOT NULL,
    description         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_permissions_code_format CHECK (permission_code ~ '^[a-z0-9*][a-z0-9.*_-]{1,127}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_permissions_code_uidx
    ON gateway_permissions (permission_code);

INSERT INTO gateway_permissions (permission_code, permission_name, description)
VALUES
    ('admin.*', 'All admin permissions', 'Full access to every local and proxied admin operation.'),
    ('admin.users.read', 'Read users', 'View users, sessions, login logs and user profile aggregates.'),
    ('admin.users.write', 'Write users', 'Change user status, roles and sessions.'),
    ('admin.audit.read', 'Read audit logs', 'View admin operation logs and login audit logs.'),
    ('admin.approvals.read', 'Read approvals', 'View admin approval requests.'),
    ('admin.approvals.write', 'Write approvals', 'Create, approve, reject and consume admin approvals.'),
    ('admin.system.read', 'Read system state', 'View configured routes and backend health.'),
    ('admin.security.mfa', 'Manage own MFA', 'Enroll, confirm and disable own admin TOTP MFA.'),
    ('admin.wallet.read', 'Read wallet withdrawals', 'View exchange withdrawal intents and custody status.'),
    ('admin.wallet.write', 'Write wallet withdrawals', 'Approve, reject and retry exchange withdrawals.'),
    ('admin.support.read', 'Read support console', 'View read-only customer support user overviews.'),
    ('admin.support.write', 'Write support tickets', 'Create and update customer support tickets and internal notes.'),
    ('admin.compliance.read', 'Read compliance', 'View KYC, AML cases and risk tags.'),
    ('admin.compliance.write', 'Write compliance', 'Update KYC, AML cases and risk tags.'),
    ('admin.permissions.read', 'Read permissions', 'View roles, permission catalog and role assignments.'),
    ('admin.permissions.write', 'Write permissions', 'Replace role permission assignments.'),
    ('admin.gateway.*.read', 'Read admin gateway services', 'Read through any configured admin gateway service.'),
    ('admin.gateway.*.write', 'Write admin gateway services', 'Write through any configured admin gateway service.')
ON CONFLICT (permission_code) DO UPDATE
   SET permission_name = EXCLUDED.permission_name,
       description = EXCLUDED.description;

CREATE TABLE IF NOT EXISTS gateway_role_permissions (
    role_id             BIGINT NOT NULL REFERENCES gateway_roles(role_id),
    permission_id       BIGINT NOT NULL REFERENCES gateway_permissions(permission_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS gateway_role_permissions_permission_idx
    ON gateway_role_permissions (permission_id);

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code = 'admin.*'
 WHERE r.role_code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN (
      'admin.support.read',
      'admin.users.read',
      'admin.users.write',
      'admin.audit.read',
      'admin.approvals.read',
      'admin.approvals.write',
      'admin.system.read',
      'admin.security.mfa',
      'admin.wallet.read',
      'admin.wallet.write',
      'admin.compliance.read',
      'admin.compliance.write',
      'admin.permissions.read',
      'admin.support.write',
      'admin.gateway.*.read',
      'admin.gateway.*.write'
  )
 WHERE r.role_code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN (
      'admin.support.read',
      'admin.support.write',
      'admin.security.mfa'
  )
 WHERE r.role_code = 'SUPPORT'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS gateway_user_mfa (
    user_id                 BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    totp_secret_ciphertext  TEXT NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_mfa_secret_present CHECK (length(totp_secret_ciphertext) > 0),
    CONSTRAINT gateway_user_mfa_verified_enabled CHECK (enabled = FALSE OR verified_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS gateway_user_mfa_enabled_idx
    ON gateway_user_mfa (enabled);

CREATE TABLE IF NOT EXISTS gateway_user_security_scenes (
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    scene_code          TEXT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scene_code),
    CONSTRAINT gateway_user_security_scene_code_check CHECK (
        scene_code IN ('LOGIN', 'CHANGE_PASSWORD', 'SECURITY_SETTINGS', 'WITHDRAWAL',
                       'API_WITHDRAWAL', 'WHITELIST', 'LARGE_TRANSFER', 'TRANSFER')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_security_scenes_updated_idx
    ON gateway_user_security_scenes (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_api_keys (
    api_key_id          UUID PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    api_key             TEXT NOT NULL UNIQUE,
    secret_ciphertext   TEXT NOT NULL,
    label               TEXT NOT NULL,
    permissions         TEXT NOT NULL DEFAULT 'READ',
    ip_allowlist        TEXT NOT NULL DEFAULT '',
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT gateway_api_key_status_check CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT gateway_api_key_label_check CHECK (length(label) BETWEEN 1 AND 80),
    CONSTRAINT gateway_api_key_permissions_check CHECK (permissions ~ '^(READ|TRADE|WITHDRAW)(,(READ|TRADE|WITHDRAW))*$')
);

CREATE INDEX IF NOT EXISTS gateway_api_keys_user_status_idx
    ON gateway_api_keys (user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS gateway_wallet_webhook_events (
    event_id            TEXT PRIMARY KEY,
    event_type          TEXT NOT NULL,
    body_sha256         TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PROCESSING',
    attempts            INTEGER NOT NULL DEFAULT 1,
    error_message       TEXT,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    CONSTRAINT gateway_wallet_webhook_status_check CHECK (
        status IN ('PROCESSING', 'PROCESSED', 'FAILED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_wallet_webhook_events_status_idx
    ON gateway_wallet_webhook_events (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawals (
    withdrawal_id        UUID PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES gateway_users(user_id),
    idempotency_key      TEXT NOT NULL,
    request_sha256       TEXT NOT NULL,
    chain                TEXT NOT NULL,
    asset_symbol         TEXT NOT NULL,
    custody_address_id   UUID NOT NULL,
    to_address           TEXT NOT NULL,
    amount               TEXT NOT NULL,
    amount_units         BIGINT NOT NULL,
    usdt_value            NUMERIC(38,18) NOT NULL,
    external_reference   TEXT NOT NULL,
    spot_debit_reference TEXT NOT NULL,
    request_payload      JSONB NOT NULL,
    status               TEXT NOT NULL,
    wallet_response      JSONB,
    wallet_withdrawal_id TEXT,
    error_code           TEXT,
    error_message        TEXT,
    admin_user_id        BIGINT REFERENCES gateway_users(user_id),
    admin_username       TEXT,
    admin_reason         TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at         TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    CONSTRAINT gateway_wallet_withdrawal_status_check CHECK (
        status IN ('PENDING_APPROVAL', 'PROCESSING', 'DEBIT_UNKNOWN', 'DEBITED', 'SUBMITTED', 'FAILED_PENDING',
                   'BROADCAST_UNKNOWN', 'COMPLETED', 'REJECTED', 'REFUND_PENDING', 'REFUNDED')
    ),
    CONSTRAINT gateway_wallet_withdrawal_amount_check CHECK (amount_units > 0 AND usdt_value > 0),
    CONSTRAINT gateway_wallet_withdrawal_external_reference_uq UNIQUE (external_reference),
    CONSTRAINT gateway_wallet_withdrawal_wallet_id_uq UNIQUE (wallet_withdrawal_id),
    CONSTRAINT gateway_wallet_withdrawal_idempotency_uq UNIQUE (user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawals_user_status_idx
    ON gateway_wallet_withdrawals (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawals_wallet_id_idx
    ON gateway_wallet_withdrawals (wallet_withdrawal_id)
    WHERE wallet_withdrawal_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_actions (
    action_id       UUID PRIMARY KEY,
    withdrawal_id   UUID NOT NULL REFERENCES gateway_wallet_withdrawals(withdrawal_id),
    admin_user_id   BIGINT NOT NULL REFERENCES gateway_users(user_id),
    admin_username  TEXT NOT NULL,
    action          TEXT NOT NULL,
    reason          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_wallet_withdrawal_action_type_check CHECK (action IN ('APPROVE', 'REJECT', 'RETRY')),
    CONSTRAINT gateway_wallet_withdrawal_action_reason_check CHECK (length(trim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawal_actions_withdrawal_idx
    ON gateway_wallet_withdrawal_actions (withdrawal_id, created_at DESC);

CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_actions_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'gateway wallet withdrawal actions are immutable';
END;
$$;

DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_actions_immutable_trigger
    ON gateway_wallet_withdrawal_actions;
CREATE TRIGGER gateway_wallet_withdrawal_actions_immutable_trigger
    BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_actions
    FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_actions_immutable_guard();

CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_events (
    event_id             UUID PRIMARY KEY,
    withdrawal_id        UUID NOT NULL REFERENCES gateway_wallet_withdrawals(withdrawal_id),
    event_type           TEXT NOT NULL,
    source               TEXT NOT NULL,
    from_status          TEXT,
    to_status            TEXT,
    wallet_withdrawal_id TEXT,
    provider_event_id    TEXT,
    payload              JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason               TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_wallet_withdrawal_event_type_check CHECK (event_type IN (
        'INTENT_CREATED', 'WALLET_ID_BOUND', 'WEBHOOK_IDEMPOTENT', 'ADMIN_RETRY', 'ADMIN_APPROVED', 'ADMIN_REJECTED',
        'DEBITED', 'DEBIT_UNKNOWN', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'COMPLETED',
        'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED', 'REJECTED'
    )),
    CONSTRAINT gateway_wallet_withdrawal_event_source_check CHECK (
        source IN ('USER', 'ADMIN', 'SPOT_ACCOUNT', 'CUSTODY_WALLET', 'RECONCILIATION', 'SYSTEM')
    )
);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawal_events_withdrawal_idx
    ON gateway_wallet_withdrawal_events (withdrawal_id, created_at ASC, event_id ASC);

ALTER TABLE gateway_wallet_withdrawal_events
    ADD COLUMN IF NOT EXISTS provider_event_id TEXT;

ALTER TABLE gateway_wallet_withdrawal_events
    DROP CONSTRAINT IF EXISTS gateway_wallet_withdrawal_event_type_check;
ALTER TABLE gateway_wallet_withdrawal_events
    ADD CONSTRAINT gateway_wallet_withdrawal_event_type_check CHECK (event_type IN (
        'INTENT_CREATED', 'WALLET_ID_BOUND', 'WEBHOOK_IDEMPOTENT', 'ADMIN_RETRY', 'ADMIN_APPROVED',
        'ADMIN_REJECTED', 'DEBITED', 'DEBIT_UNKNOWN', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'COMPLETED',
        'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED', 'REJECTED'
    ));

CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_events_immutable_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'gateway wallet withdrawal events are immutable';
END;
$$;

DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_events_immutable_trigger
    ON gateway_wallet_withdrawal_events;
CREATE TRIGGER gateway_wallet_withdrawal_events_immutable_trigger
    BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_events
    FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_events_immutable_guard();

CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_status_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('PROCESSING', 'REJECTED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('DEBIT_UNKNOWN', 'DEBITED', 'REJECTED'))
        OR (OLD.status = 'DEBIT_UNKNOWN' AND NEW.status = 'DEBITED')
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

DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_status_guard_trigger
    ON gateway_wallet_withdrawals;
CREATE CONSTRAINT TRIGGER gateway_wallet_withdrawal_status_guard_trigger
    AFTER UPDATE OF status ON gateway_wallet_withdrawals
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_status_guard();

CREATE TABLE IF NOT EXISTS gateway_user_kyc_profiles (
    user_id                 BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    kyc_level               TEXT NOT NULL DEFAULT 'NONE',
    status                  TEXT NOT NULL DEFAULT 'UNVERIFIED',
    country                 TEXT,
    document_type           TEXT,
    provider                TEXT,
    provider_reference      TEXT,
    applicant_type          TEXT NOT NULL DEFAULT 'INDIVIDUAL',
    submitted_documents     JSONB NOT NULL DEFAULT '[]'::jsonb,
    face_verification_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED',
    reviewed_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    reviewed_at             TIMESTAMPTZ,
    rejection_reason        TEXT,
    expires_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_kyc_level_check CHECK (kyc_level IN ('NONE', 'BASIC', 'STANDARD', 'ENHANCED', 'INSTITUTIONAL')),
    CONSTRAINT gateway_user_kyc_status_check CHECK (status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT gateway_user_kyc_country_check CHECK (country IS NULL OR country ~ '^[A-Z]{2}$')
);

ALTER TABLE gateway_user_kyc_profiles ADD COLUMN IF NOT EXISTS applicant_type TEXT NOT NULL DEFAULT 'INDIVIDUAL';
ALTER TABLE gateway_user_kyc_profiles ADD COLUMN IF NOT EXISTS submitted_documents JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE gateway_user_kyc_profiles ADD COLUMN IF NOT EXISTS face_verification_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED';

CREATE INDEX IF NOT EXISTS gateway_user_kyc_status_idx
    ON gateway_user_kyc_profiles (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_user_kyc_documents (
    document_id         BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    document_type       TEXT NOT NULL,
    original_filename   TEXT NOT NULL,
    content_type        TEXT NOT NULL,
    file_size           BIGINT NOT NULL,
    sha256              TEXT NOT NULL,
    object_key          TEXT NOT NULL UNIQUE,
    status              TEXT NOT NULL DEFAULT 'UPLOADED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT gateway_user_kyc_documents_type_check CHECK (
        document_type IN ('ID_CARD', 'ID_CARD_FRONT', 'ID_CARD_BACK', 'ID_CARD_SELFIE', 'PASSPORT', 'ADDRESS_PROOF', 'BUSINESS_LICENSE', 'FACE_IMAGE', 'DRIVING_LICENSE', 'DRIVING_LICENSE_FRONT', 'DRIVING_LICENSE_BACK', 'RESIDENCE_PERMIT', 'RESIDENCE_PERMIT_FRONT', 'RESIDENCE_PERMIT_BACK')
    ),
    CONSTRAINT gateway_user_kyc_documents_content_type_check CHECK (
        content_type IN ('application/pdf', 'image/jpeg', 'image/png')
    ),
    CONSTRAINT gateway_user_kyc_documents_size_check CHECK (file_size > 0),
    CONSTRAINT gateway_user_kyc_documents_sha256_check CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT gateway_user_kyc_documents_status_check CHECK (
        status IN ('UPLOADED', 'SUBMITTED', 'DELETED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_kyc_documents_user_idx
    ON gateway_user_kyc_documents (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_kyc_documents_status_idx
    ON gateway_user_kyc_documents (status, created_at DESC);

CREATE TABLE IF NOT EXISTS gateway_user_risk_tags (
    tag_id                  BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    tag_code                TEXT NOT NULL,
    severity                TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'ACTIVE',
    source                  TEXT,
    reason                  TEXT NOT NULL,
    created_by_user_id      BIGINT REFERENCES gateway_users(user_id),
    resolved_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_risk_tags_code_check CHECK (tag_code ~ '^[A-Z0-9_.:-]{2,64}$'),
    CONSTRAINT gateway_user_risk_tags_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT gateway_user_risk_tags_status_check CHECK (status IN ('ACTIVE', 'RESOLVED')),
    CONSTRAINT gateway_user_risk_tags_reason_present CHECK (length(reason) > 0)
);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_user_time_idx
    ON gateway_user_risk_tags (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_status_idx
    ON gateway_user_risk_tags (status, severity, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_created_page_idx
    ON gateway_user_risk_tags (created_at DESC, tag_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_updated_page_idx
    ON gateway_user_risk_tags (updated_at DESC, tag_id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_user_risk_tags_active_uidx
    ON gateway_user_risk_tags (user_id, tag_code)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS gateway_user_aml_cases (
    case_id                 BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    status                  TEXT NOT NULL DEFAULT 'OPEN',
    risk_score              INTEGER NOT NULL DEFAULT 0,
    source                  TEXT,
    summary                 TEXT NOT NULL,
    assigned_admin_user_id  BIGINT REFERENCES gateway_users(user_id),
    created_by_user_id      BIGINT REFERENCES gateway_users(user_id),
    reviewed_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    reviewed_at             TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_aml_cases_status_check CHECK (
        status IN ('OPEN', 'REVIEWING', 'CLEARED', 'ESCALATED', 'RESTRICTED', 'CLOSED')
    ),
    CONSTRAINT gateway_user_aml_cases_risk_score_check CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT gateway_user_aml_cases_summary_present CHECK (length(summary) > 0)
);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_user_time_idx
    ON gateway_user_aml_cases (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_status_idx
    ON gateway_user_aml_cases (status, risk_score DESC, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_updated_page_idx
    ON gateway_user_aml_cases (updated_at DESC, case_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_created_page_idx
    ON gateway_user_aml_cases (created_at DESC, case_id DESC);

CREATE TABLE IF NOT EXISTS gateway_support_tickets (
    ticket_id               BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    status                  TEXT NOT NULL DEFAULT 'OPEN',
    priority                TEXT NOT NULL DEFAULT 'MEDIUM',
    category                TEXT NOT NULL DEFAULT 'GENERAL',
    title                   TEXT NOT NULL,
    assigned_admin_user_id  BIGINT REFERENCES gateway_users(user_id),
    created_by_user_id      BIGINT NOT NULL REFERENCES gateway_users(user_id),
    resolved_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at               TIMESTAMPTZ,
    CONSTRAINT gateway_support_tickets_status_check CHECK (
        status IN ('OPEN', 'PENDING_USER', 'PENDING_INTERNAL', 'RESOLVED', 'CLOSED')
    ),
    CONSTRAINT gateway_support_tickets_priority_check CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')
    ),
    CONSTRAINT gateway_support_tickets_category_check CHECK (category ~ '^[A-Z0-9_.:-]{2,64}$'),
    CONSTRAINT gateway_support_tickets_title_present CHECK (length(title) BETWEEN 1 AND 160),
    CONSTRAINT gateway_support_tickets_closed_state_check CHECK (
        (closed_at IS NULL AND status <> 'CLOSED') OR (closed_at IS NOT NULL AND status = 'CLOSED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_support_tickets_user_time_idx
    ON gateway_support_tickets (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_support_tickets_status_idx
    ON gateway_support_tickets (status, priority, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_support_ticket_notes (
    note_id             BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES gateway_support_tickets(ticket_id) ON DELETE CASCADE,
    admin_user_id       BIGINT NOT NULL REFERENCES gateway_users(user_id),
    note_type           TEXT NOT NULL DEFAULT 'NOTE',
    visibility          TEXT NOT NULL DEFAULT 'INTERNAL',
    body                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_support_ticket_notes_type_check CHECK (
        note_type IN ('NOTE', 'STATUS_CHANGE', 'ESCALATION', 'FOLLOW_UP')
    ),
    CONSTRAINT gateway_support_ticket_notes_visibility_check CHECK (
        visibility IN ('INTERNAL', 'CUSTOMER')
    ),
    CONSTRAINT gateway_support_ticket_notes_body_present CHECK (length(body) BETWEEN 1 AND 2000)
);

CREATE INDEX IF NOT EXISTS gateway_support_ticket_notes_ticket_time_idx
    ON gateway_support_ticket_notes (ticket_id, created_at ASC);

CREATE INDEX IF NOT EXISTS gateway_support_ticket_notes_page_idx
    ON gateway_support_ticket_notes (ticket_id, created_at ASC, note_id ASC);

CREATE TABLE IF NOT EXISTS gateway_refresh_sessions (
    session_id          BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    token_hash          TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    user_agent          TEXT,
    ip_address          TEXT,
    device_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_refresh_sessions_hash_present CHECK (length(token_hash) > 0)
);

ALTER TABLE gateway_refresh_sessions ADD COLUMN IF NOT EXISTS device_id UUID;

CREATE INDEX IF NOT EXISTS gateway_refresh_sessions_user_device_idx
    ON gateway_refresh_sessions (user_id, device_id, created_at DESC)
    WHERE device_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_user_access_blocks (
    block_id            BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    block_type          TEXT NOT NULL CHECK (block_type IN ('DEVICE', 'IP')),
    block_value         TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT gateway_access_block_value_present CHECK (length(block_value) BETWEEN 1 AND 128)
);

COMMENT ON COLUMN gateway_user_access_blocks.block_type IS '访问封禁类型：DEVICE 为设备标识，IP 为来源地址。';
COMMENT ON COLUMN gateway_user_access_blocks.block_value IS '被封禁的设备标识或来源 IP 地址，与 block_type 联合解释。';

CREATE UNIQUE INDEX IF NOT EXISTS gateway_user_access_blocks_active_uidx
    ON gateway_user_access_blocks (user_id, block_type, block_value)
    WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS gateway_refresh_sessions_hash_uidx
    ON gateway_refresh_sessions (token_hash);

CREATE INDEX IF NOT EXISTS gateway_refresh_sessions_user_time_idx
    ON gateway_refresh_sessions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_refresh_sessions_expiry_idx
    ON gateway_refresh_sessions (expires_at ASC)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS gateway_login_logs (
    login_id            BIGSERIAL PRIMARY KEY,
    user_id             BIGINT REFERENCES gateway_users(user_id),
    result              TEXT NOT NULL,
    reason              TEXT,
    user_agent          TEXT,
    ip_address          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_login_logs_result_check CHECK (result IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS gateway_login_logs_user_time_idx
    ON gateway_login_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_login_logs_time_idx
    ON gateway_login_logs (created_at DESC);

CREATE SEQUENCE IF NOT EXISTS gateway_user_notification_seq;

CREATE TABLE IF NOT EXISTS gateway_user_notifications (
    notification_id BIGINT PRIMARY KEY DEFAULT nextval('gateway_user_notification_seq'),
    user_id         BIGINT NOT NULL REFERENCES gateway_users(user_id),
    category        VARCHAR(32) NOT NULL,
    title           VARCHAR(160) NOT NULL,
    body            VARCHAR(4000) NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_notifications_category_check CHECK (
        category IN ('SYSTEM', 'SECURITY', 'TRADING', 'FUNDING', 'ACCOUNT', 'COMPLIANCE')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_notifications_user_time_idx
    ON gateway_user_notifications (user_id, created_at DESC, notification_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_notifications_unread_idx
    ON gateway_user_notifications (user_id, notification_id)
    WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS gateway_admin_operation_logs (
    operation_id          BIGSERIAL PRIMARY KEY,
    admin_user_id        BIGINT REFERENCES gateway_users(user_id),
    admin_username       TEXT,
    admin_roles          TEXT,
    service              TEXT NOT NULL,
    http_method          TEXT NOT NULL,
    request_path         TEXT NOT NULL,
    query_string         TEXT,
    target_uri           TEXT,
    request_body_sha256  TEXT,
    response_status      INTEGER,
    duration_ms          BIGINT,
    success              BOOLEAN NOT NULL,
    error_message        TEXT,
    trace_id             TEXT,
    user_agent           TEXT,
    ip_address           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_operation_service_check CHECK (service ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT gateway_admin_operation_method_check CHECK (http_method ~ '^[A-Z]{3,16}$'),
    CONSTRAINT gateway_admin_operation_duration_non_negative CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_admin_time_idx
    ON gateway_admin_operation_logs (admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_service_time_idx
    ON gateway_admin_operation_logs (service, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_time_idx
    ON gateway_admin_operation_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_trace_idx
    ON gateway_admin_operation_logs (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_admin_approval_requests (
    approval_id          BIGSERIAL PRIMARY KEY,
    requester_user_id    BIGINT NOT NULL REFERENCES gateway_users(user_id),
    requester_username   TEXT,
    approver_user_id     BIGINT REFERENCES gateway_users(user_id),
    approver_username    TEXT,
    service              TEXT NOT NULL,
    http_method          TEXT NOT NULL,
    request_path         TEXT NOT NULL,
    query_string         TEXT,
    request_body_sha256  TEXT,
    reason               TEXT NOT NULL,
    decision_reason      TEXT,
    status               TEXT NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    decided_at           TIMESTAMPTZ,
    consumed_at          TIMESTAMPTZ,
    consumed_trace_id    TEXT,
    CONSTRAINT gateway_admin_approval_status_check CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONSUMED')
    ),
    CONSTRAINT gateway_admin_approval_service_check CHECK (service ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT gateway_admin_approval_method_check CHECK (http_method ~ '^[A-Z]{3,16}$')
);

ALTER TABLE gateway_admin_approval_requests ALTER COLUMN requester_username DROP NOT NULL;

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_requester_time_idx
    ON gateway_admin_approval_requests (requester_user_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_status_time_idx
    ON gateway_admin_approval_requests (status, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_service_time_idx
    ON gateway_admin_approval_requests (service, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_consumed_trace_idx
    ON gateway_admin_approval_requests (consumed_trace_id)
    WHERE consumed_trace_id IS NOT NULL;

-- 09. Aeron Core audit, history projections and WebSocket delivery audit.

CREATE TABLE IF NOT EXISTS core_event_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    applied_command_count BIGINT NOT NULL,
    business_state_hash BIGINT NOT NULL,
    command_id UUID NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    command_status VARCHAR(16) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    before_business_state_hash BIGINT NOT NULL,
    before_funds_state_hash BIGINT NOT NULL,
    funds_state_hash BIGINT NOT NULL,
    matcher_sequence_before BIGINT NOT NULL,
    matcher_sequence BIGINT NOT NULL,
    matcher_prefix_before BIGINT NOT NULL,
    matcher_prefix_after BIGINT NOT NULL,
    cluster_position BIGINT NOT NULL,
    raw_event BYTEA NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_line, export_sequence)
);

CREATE INDEX IF NOT EXISTS idx_core_event_projection_command
    ON core_event_projection (product_line, command_id);

CREATE TABLE IF NOT EXISTS core_funds_posting_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    posting_index INTEGER NOT NULL,
    asset VARCHAR(20) NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    owner_id BIGINT NOT NULL,
    subledger VARCHAR(32) NOT NULL,
    units BIGINT NOT NULL,
    PRIMARY KEY (product_line, export_sequence, posting_index)
);

CREATE INDEX IF NOT EXISTS idx_core_funds_posting_owner
    ON core_funds_posting_projection (product_line, owner_kind, owner_id, asset, export_sequence);

CREATE TABLE IF NOT EXISTS core_user_fact_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_revision BIGINT NOT NULL,
    raw_user_delta BYTEA NOT NULL,
    PRIMARY KEY (product_line, export_sequence, user_id)
);

CREATE TABLE IF NOT EXISTS core_order_projection (
    product_line VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_order_id VARCHAR(64),
    instrument_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    cluster_position BIGINT NOT NULL,
    order_revision BIGINT NOT NULL,
    export_sequence BIGINT NOT NULL,
    raw_order_state BYTEA NOT NULL,
    PRIMARY KEY (product_line, order_id),
    UNIQUE (product_line, user_id, client_order_id)
);

CREATE INDEX IF NOT EXISTS idx_core_order_projection_user_status
    ON core_order_projection (product_line, user_id, status, order_id DESC);

CREATE INDEX IF NOT EXISTS idx_core_order_projection_symbol_status
    ON core_order_projection (product_line, instrument_id, status, order_id DESC);

CREATE TABLE IF NOT EXISTS core_execution_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    execution_index INTEGER NOT NULL,
    taker_order_id BIGINT NOT NULL,
    maker_order_id BIGINT NOT NULL,
    taker_user_id BIGINT NOT NULL,
    maker_user_id BIGINT NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    instrument_change_id BIGINT NOT NULL,
    taker_side VARCHAR(8) NOT NULL,
    taker_fee_rate_ppm BIGINT NOT NULL,
    maker_fee_rate_ppm BIGINT NOT NULL,
    price_ticks BIGINT NOT NULL,
    quantity_steps BIGINT NOT NULL,
    occurred_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, export_sequence, execution_index)
);

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_taker
    ON core_execution_projection (product_line, taker_user_id, export_sequence DESC);

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_maker
    ON core_execution_projection (product_line, maker_user_id, export_sequence DESC);

CREATE INDEX IF NOT EXISTS idx_core_execution_projection_symbol_time
    ON core_execution_projection (product_line, instrument_id, occurred_at_epoch_ms DESC, export_sequence DESC);

CREATE TABLE IF NOT EXISTS core_funding_settlement_projection (
    product_line VARCHAR(32) NOT NULL,
    settlement_id BIGINT NOT NULL,
    export_sequence BIGINT NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    instrument_change_id BIGINT NOT NULL,
    funding_rate_ppm BIGINT NOT NULL,
    command_status VARCHAR(32) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    total_long_payment_units BIGINT NOT NULL,
    total_short_payment_units BIGINT NOT NULL,
    position_count INTEGER NOT NULL,
    occurred_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, instrument_id, settlement_id),
    UNIQUE (product_line, export_sequence)
);

CREATE INDEX IF NOT EXISTS idx_core_funding_settlement_symbol
    ON core_funding_settlement_projection (product_line, instrument_id, settlement_id DESC);

CREATE TABLE IF NOT EXISTS core_funding_payment_projection (
    payment_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    payment_index INTEGER NOT NULL,
    settlement_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    margin_mode VARCHAR(16) NOT NULL,
    position_side VARCHAR(16) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    signed_quantity_steps BIGINT NOT NULL,
    notional_units BIGINT NOT NULL,
    funding_rate_ppm BIGINT NOT NULL,
    amount_units BIGINT NOT NULL,
    occurred_at_epoch_ms BIGINT NOT NULL,
    UNIQUE (product_line, export_sequence, payment_index)
);

CREATE INDEX IF NOT EXISTS idx_core_funding_payment_user
    ON core_funding_payment_projection (product_line, user_id, payment_id DESC);

CREATE INDEX IF NOT EXISTS idx_core_funding_payment_user_symbol
    ON core_funding_payment_projection (product_line, user_id, instrument_id, payment_id DESC);

CREATE TABLE IF NOT EXISTS core_liquidation_projection (
    product_line VARCHAR(32) NOT NULL,
    liquidation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    margin_mode VARCHAR(16) NOT NULL,
    position_side VARCHAR(16) NOT NULL,
    instrument_change_id BIGINT NOT NULL,
    trigger_price_sequence BIGINT NOT NULL,
    signed_quantity_steps BIGINT NOT NULL,
    close_quantity_steps BIGINT NOT NULL,
    deficit_units BIGINT NOT NULL,
    execution_price_ticks BIGINT NOT NULL,
    liquidation_fee_rate_ppm BIGINT NOT NULL,
    liquidation_fee_units BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, liquidation_id)
);

CREATE INDEX IF NOT EXISTS idx_core_liquidation_pending
    ON core_liquidation_projection (product_line, status, liquidation_id);

CREATE INDEX IF NOT EXISTS idx_core_liquidation_user
    ON core_liquidation_projection (product_line, user_id, liquidation_id DESC);

CREATE TABLE IF NOT EXISTS core_treasury_projection (
    product_line VARCHAR(32) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    fee_balance_units BIGINT NOT NULL,
    insurance_balance_units BIGINT NOT NULL,
    insurance_deficit_units BIGINT NOT NULL,
    liquidation_fee_units BIGINT NOT NULL,
    funding_residual_units BIGINT NOT NULL,
    rounding_residual_units BIGINT NOT NULL,
    clearing_pnl_units BIGINT NOT NULL,
    export_sequence BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (product_line, asset)
);

CREATE TABLE IF NOT EXISTS core_projection_watermark (
    product_line VARCHAR(32) NOT NULL,
    last_export_sequence BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_line),
    CHECK (last_export_sequence >= 0)
);

INSERT INTO core_projection_watermark (product_line, last_export_sequence)
VALUES ('SPOT', 0), ('LINEAR_PERPETUAL', 0), ('INVERSE_PERPETUAL', 0),
       ('LINEAR_DELIVERY', 0), ('INVERSE_DELIVERY', 0), ('OPTION', 0)
ON CONFLICT (product_line) DO NOTHING;

-- 10. Initialization identity.

CREATE TABLE IF NOT EXISTS surprising_schema_metadata (
    baseline_version TEXT PRIMARY KEY,
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    postgres_version TEXT NOT NULL
);

INSERT INTO surprising_schema_metadata (baseline_version, postgres_version)
VALUES ('2026.08.18', current_setting('server_version'))
ON CONFLICT (baseline_version) DO NOTHING;

-- 11. Database catalog documentation and shared domain constraints.
-- PostgreSQL stores descriptions through COMMENT ON rather than inline column syntax.

DO $$
DECLARE
    item RECORD;
    table_description TEXT;
    column_description TEXT;
    constraint_name TEXT;
    generic_columns TEXT;
BEGIN
    FOR item IN
        SELECT c.relname AS table_name
          FROM pg_class c
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
         ORDER BY c.relname
    LOOP
        table_description := CASE
            WHEN item.table_name = 'instruments' THEN '合约当前配置主表；每条产品线每个币对唯一。'
            WHEN item.table_name = 'surprising_schema_metadata' THEN '数据库首发基线版本和 PostgreSQL 版本记录。'
            WHEN item.table_name LIKE 'instrument_%' THEN '合约配置、风险档位、指数源或生命周期审计数据。'
            WHEN item.table_name LIKE 'price_%' THEN '指数价、标记价、汇率或行情处理状态的历史数据。'
            WHEN item.table_name LIKE 'candlestick_%' THEN 'K 线聚合结果和查询投影。'
            WHEN item.table_name LIKE 'trading_%' THEN '交易配置、订单、成交或交易审计投影；不作为 Aeron 在线状态权威。'
            WHEN item.table_name LIKE 'account_%' THEN '账户历史、审计或对账投影；不作为 Aeron 在线资金权威。'
            WHEN item.table_name LIKE 'funding_%' THEN '资金费率、结算和支付历史投影。'
            WHEN item.table_name LIKE 'risk_%' THEN '风险快照、强平候选或风险审计投影。'
            WHEN item.table_name LIKE 'liquidation_%' THEN '强平执行和人工操作历史。'
            WHEN item.table_name LIKE 'insurance_%' THEN '保险基金余额、流水和亏损覆盖历史。'
            WHEN item.table_name LIKE 'adl_%' THEN '自动减仓事件、执行 Saga 和审计历史。'
            WHEN item.table_name LIKE 'market_maker_%' THEN '做市策略租约、覆盖配置和运行审计。'
            WHEN item.table_name LIKE 'gateway_%' THEN 'Gateway 用户、安全、钱包、合规、通知或管理审计数据。'
            WHEN item.table_name LIKE 'core_%' THEN 'Aeron Core Export 经 Kafka 投影的历史、审计或查询数据。'
            ELSE format('Surprising Exchange 业务表：%s。', item.table_name)
        END;
        EXECUTE format('COMMENT ON TABLE %I.%I IS %L', 'public', item.table_name, table_description);
    END LOOP;

    FOR item IN
        SELECT c.relname AS table_name,
               a.attname AS column_name,
               format_type(a.atttypid, a.atttypmod) AS data_type,
               col_description(c.oid, a.attnum) AS existing_description
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
           AND a.attnum > 0
           AND NOT a.attisdropped
         ORDER BY c.relname, a.attnum
    LOOP
        column_description := COALESCE(item.existing_description, CASE item.column_name
            WHEN 'product_line' THEN '产品线代码；限定为 SPOT、两类永续、两类交割或 OPTION。'
            WHEN 'symbol' THEN '交易标的显示名称；业务身份使用产品线与永久标的 ID。'
            WHEN 'version' THEN '配置或业务对象版本号；新版本必须单调递增。'
            WHEN 'instrument_change_id' THEN '命令、订单、持仓或事件绑定的合约版本号。'
            WHEN 'instrument_type' THEN '产品大类：现货、永续、交割或期权。'
            WHEN 'contract_type' THEN '精确合约类型及正向/反向计价方式。'
            WHEN 'base_asset' THEN '交易对基础资产代码。'
            WHEN 'before_values' THEN '操作发生前的合约配置 JSON；首次创建为空。'
            WHEN 'after_values' THEN '操作完成后的合约配置 JSON，仅供审计展示。'
            WHEN 'last_change_id' THEN '最近一次配置操作的日志标识，包含纯状态修改。'
            WHEN 'change_id' THEN '操作日志标识，用于事件去重与顺序校验。'
            WHEN 'quote_asset' THEN '交易对报价资产代码。'
            WHEN 'settle_asset' THEN '资金、盈亏和费用的结算资产代码。'
            WHEN 'asset' THEN '资产代码。'
            WHEN 'margin_asset' THEN '保证金资产代码。'
            WHEN 'contract_value_asset' THEN '合约面值计价资产。'
            WHEN 'underlying_instrument_id' THEN '期权关联的永久标的币对 ID。'
            WHEN 'underlying_product_line' THEN '期权标的币对所属产品线。'
            WHEN 'status' THEN '当前业务状态；允许值由所在表的 CHECK 约束限定。'
            WHEN 'side' THEN '订单或仓位方向。'
            WHEN 'taker_side' THEN '成交中吃单方的买卖方向。'
            WHEN 'position_side' THEN '持仓方向：NET、LONG 或 SHORT。'
            WHEN 'margin_mode' THEN '保证金模式：CROSS 或 ISOLATED。'
            WHEN 'account_type' THEN '产品账户类型，用于资金和风险隔离。'
            WHEN 'order_type' THEN '订单类型。'
            WHEN 'time_in_force' THEN '订单有效方式。'
            WHEN 'settlement_method' THEN '到期结算方式。'
            WHEN 'option_type' THEN '期权方向：CALL 或 PUT。'
            WHEN 'option_exercise_style' THEN '期权行权风格。'
            WHEN 'price_ticks' THEN '按合约 price_tick_units 量化后的整数价格。'
            WHEN 'mark_price_ticks' THEN '按合约精度量化后的标记价格。'
            WHEN 'execution_price_ticks' THEN '实际执行或强平成交价格 tick。'
            WHEN 'entry_price_ticks' THEN '持仓平均开仓价格 tick。'
            WHEN 'strike_price_units' THEN '期权行权价的最小精度整数值。'
            WHEN 'quantity_steps' THEN '按 quantity_step_units 量化后的订单或成交数量。'
            WHEN 'signed_quantity_steps' THEN '带多空符号的持仓数量 step。'
            WHEN 'close_quantity_steps' THEN '本次关闭或强平的数量 step。'
            WHEN 'executed_quantity_steps' THEN '已经成交的数量 step。'
            WHEN 'remaining_quantity_steps' THEN '尚未成交的数量 step。'
            WHEN 'amount_units' THEN '资产最小精度整数金额；正负含义由业务事件决定。'
            WHEN 'available_units' THEN '可用余额的最小精度整数值。'
            WHEN 'locked_units' THEN '已锁定余额的最小精度整数值。'
            WHEN 'reserved_units' THEN '订单或业务流程预占的最小精度整数金额。'
            WHEN 'deficit_units' THEN '尚未覆盖亏损的最小精度整数金额。'
            WHEN 'fee_units' THEN '手续费的最小精度整数金额。'
            WHEN 'position_margin_units' THEN '持仓保证金的最小精度整数金额。'
            WHEN 'realized_pnl_units' THEN '已实现盈亏的最小精度整数金额。'
            WHEN 'unrealized_pnl_units' THEN '未实现盈亏的最小精度整数金额。'
            WHEN 'notional_units' THEN '名义价值的最小精度整数金额。'
            WHEN 'maker_fee_rate_ppm' THEN '挂单方手续费率，单位 ppm（一百万分之一）。'
            WHEN 'taker_fee_rate_ppm' THEN '吃单方手续费率，单位 ppm（一百万分之一）。'
            WHEN 'funding_rate_ppm' THEN '资金费率，单位 ppm（一百万分之一）。'
            WHEN 'initial_margin_rate_ppm' THEN '初始保证金率，单位 ppm。'
            WHEN 'maintenance_margin_rate_ppm' THEN '维持保证金率，单位 ppm。'
            WHEN 'liquidation_fee_rate_ppm' THEN '强平手续费率，单位 ppm。'
            WHEN 'max_leverage_ppm' THEN '最大杠杆倍数，使用 ppm 定点表示。'
            WHEN 'weight_ppm' THEN '指数源权重，单位 ppm。'
            WHEN 'command_id' THEN '跨重试保持不变的幂等命令 UUID。'
            WHEN 'client_order_id' THEN '用户侧订单幂等标识。'
            WHEN 'order_id' THEN '系统订单唯一标识。'
            WHEN 'user_id' THEN '用户唯一标识；系统账户的取值规则由业务模块定义。'
            WHEN 'event_id' THEN '不可变业务事件唯一标识。'
            WHEN 'trace_id' THEN '跨服务请求链路追踪标识。'
            WHEN 'reference_id' THEN '外部或跨账本业务引用标识。'
            WHEN 'idempotency_key' THEN '调用方提供的幂等键。'
            WHEN 'request_fingerprint' THEN '规范化请求内容的指纹，用于检测幂等键冲突。'
            WHEN 'export_sequence' THEN 'Aeron Core Export 在产品线内连续递增的序列号。'
            WHEN 'before_business_state_hash' THEN '应用当前 Core Fact 前的确定性业务状态哈希。'
            WHEN 'before_funds_state_hash' THEN '应用当前 Core Fact 前的确定性资金状态哈希。'
            WHEN 'funds_state_hash' THEN '应用当前 Core Fact 后的确定性资金状态哈希。'
            WHEN 'matcher_sequence_before' THEN '当前 Core Fact 覆盖的撮合结果区间起始序列；等于上一条事实的 matcher_sequence。'
            WHEN 'matcher_sequence' THEN '当前 Core Fact 应用后的撮合结果累计序列。'
            WHEN 'matcher_prefix_before' THEN '当前 Core Fact 覆盖撮合结果前的不可变前缀摘要。'
            WHEN 'matcher_prefix_after' THEN '当前 Core Fact 覆盖撮合结果后的不可变前缀摘要。'
            WHEN 'posting_index' THEN '同一 Core Fact 内资金分录的零基连续索引。'
            WHEN 'owner_kind' THEN '资金分录所有者类型：用户或 Treasury。'
            WHEN 'subledger' THEN '资金分录所属的可用、冻结、费用、保险等子账本。'
            WHEN 'units' THEN '资金分录的有符号最小精度整数金额。'
            WHEN 'source_sequence' THEN '事件源在 source_id 范围内单调递增的序列号。'
            WHEN 'cluster_position' THEN 'Aeron Cluster Log 中提交该状态的逻辑位置。'
            WHEN 'revision' THEN '业务实体修订号；每次权威变更递增。'
            WHEN 'cache_revision' THEN '缓存或查询投影修订号。'
            WHEN 'created_at' THEN '记录创建时间，带时区。'
            WHEN 'updated_at' THEN '记录最后更新时间，带时区。'
            WHEN 'effective_time' THEN '配置或规则开始生效的时间。'
            WHEN 'expiry_time' THEN '合约停止交易或期权到期的时间。'
            WHEN 'delivery_time' THEN '交割或现金结算执行时间。'
            WHEN 'event_time' THEN '业务事件实际发生时间。'
            WHEN 'occurred_at_epoch_ms' THEN '业务事件发生时间，Unix epoch 毫秒。'
            WHEN 'projected_at' THEN '异步投影写入 PostgreSQL 的时间。'
            WHEN 'published_at' THEN '事件成功发布到 Kafka 的时间；NULL 表示待发布。'
            WHEN 'payload' THEN '结构化业务事件 JSON 载荷。'
            WHEN 'raw_event' THEN '未经转换的 Core Export 二进制事件。'
            WHEN 'raw_order_state' THEN '订单状态的版本化二进制快照。'
            WHEN 'raw_user_delta' THEN '用户状态增量的版本化二进制载荷。'
            WHEN 'error_code' THEN '稳定的机器可读错误代码。'
            WHEN 'error_message' THEN '用于审计和排障的错误说明。'
            ELSE CASE
                WHEN item.column_name = 'id' THEN '表内记录的自增主键。'
                WHEN item.column_name IN ('action', 'action_type') THEN '本条审计或业务记录执行的动作类型。'
                WHEN item.column_name IN ('adjustment_kind', 'category', 'note_type', 'rule_type', 'source_type') THEN
                    format('`%s` 的业务分类。', item.column_name)
                WHEN item.column_name IN ('admin_username', 'requester_username', 'approver_username', 'username') THEN
                    format('执行或关联该操作的%s。', item.column_name)
                WHEN item.column_name IN ('admin_reason', 'decision_reason', 'last_error', 'reason', 'reject_reason',
                                          'rejection_reason', 'skipped_reason') THEN
                    format('该记录的%s说明，用于审计和排障。', item.column_name)
                WHEN item.column_name IN ('aggregate_type', 'command_type', 'event_type', 'reference_type') THEN
                    format('用于路由和反序列化的%s。', item.column_name)
                WHEN item.column_name IN ('amount', 'price', 'price1', 'price2', 'rate', 'funding_rate',
                                          'index_price', 'mark_price', 'open_price', 'high_price', 'low_price',
                                          'close_price', 'ask_price', 'bid_price', 'best_ask_price',
                                          'best_bid_price', 'last_trade_price', 'base_volume', 'quote_volume',
                                          'basis_average', 'usdt_value') THEN
                    format('`%s` 的十进制定点值；精度由对应合约或资产配置决定。', item.column_name)
                WHEN item.column_name IN ('ask_levels', 'bid_levels', 'order_levels') THEN
                    format('`%s` 的结构化盘口档位数据。', item.column_name)
                WHEN item.column_name IN ('attempts', 'bracket_no', 'execution_index', 'payment_index',
                                          'priority', 'risk_score', 'scan_batch_size', 'sequence',
                                          'sequence_value', 'submitted_orders', 'canceled_orders',
                                          'rejected_orders', 'min_valid_index_sources') THEN
                    format('`%s` 对应的非负序号、次数或数量。', item.column_name)
                WHEN item.column_name IN ('baseline_version', 'postgres_version') THEN
                    format('初始化基线记录的%s。', item.column_name)
                WHEN item.column_name IN ('base_currency', 'quote_currency', 'target_quote_currency', 'asset_symbol') THEN
                    format('`%s` 使用的标准资产代码。', item.column_name)
                WHEN item.column_name IN ('base_url', 'conversion_base_url', 'websocket_url', 'target_uri') THEN
                    format('`%s` 的外部服务连接地址。', item.column_name)
                WHEN item.column_name IN ('body', 'calculation_inputs', 'request_payload', 'result_payload',
                                          'submitted_documents', 'wallet_response') THEN
                    format('`%s` 的结构化 JSON 内容。', item.column_name)
                WHEN item.column_name IN ('body_sha256', 'business_state_hash', 'code_hash', 'payload_sha256',
                                          'request_body_sha256', 'request_sha256', 'sha256') THEN
                    format('`%s` 的完整性或一致性校验摘要。', item.column_name)
                WHEN item.column_name IN ('channel', 'component', 'destination', 'module', 'service', 'topic',
                                          'transport', 'provider') THEN
                    format('事件、调用或配置使用的%s标识。', item.column_name)
                WHEN item.column_name IN ('clamp_high', 'clamp_low', 'configured_weight', 'effective_weight',
                                          'total_configured_weight') THEN
                    format('指数源聚合计算使用的 `%s` 权重或边界值。', item.column_name)
                WHEN item.column_name IN ('conversion_mode', 'conversion_operation', 'margin_mode',
                                          'position_mode', 'scan_margin_mode', 'target_margin_mode',
                                          'maker_margin_mode', 'taker_margin_mode') THEN
                    format('`%s` 的处理模式。', item.column_name)
                WHEN item.column_name IN ('conversion_parser', 'parser', 'websocket_parser') THEN
                    format('解析 `%s` 外部响应时使用的解析器类型。', item.column_name)
                WHEN item.column_name IN ('conversion_path', 'path', 'request_path') THEN
                    format('调用或取值使用的 `%s` 路径。', item.column_name)
                WHEN item.column_name IN ('country', 'document_type', 'kyc_level', 'applicant_type') THEN
                    format('KYC/合规流程记录的 `%s`。', item.column_name)
                WHEN item.column_name IN ('description', 'label', 'summary', 'title') THEN
                    format('面向管理端或用户展示的%s文本。', item.column_name)
                WHEN item.column_name IN ('email', 'phone', 'ip_address', 'request_ip', 'user_agent') THEN
                    format('安全审计或用户资料中的 `%s`。', item.column_name)
                WHEN item.column_name IN ('enabled', 'post_only', 'reduce_only', 'scan_completed', 'success',
                                          'maker_order_completed', 'taker_order_completed') THEN
                    format('`%s` 的布尔开关或完成状态。', item.column_name)
                WHEN item.column_name IN ('event_key', 'reference_key', 'external_reference', 'provider_reference',
                                          'source_reference', 'spot_debit_reference') THEN
                    format('跨系统关联和幂等处理使用的 `%s`。', item.column_name)
                WHEN item.column_name IN ('file_size', 'funding_interval_hours', 'basis_window_seconds',
                                          'time_until_funding_seconds', 'duration_ms', 'latency_millis',
                                          'scan_delay_ms') THEN
                    format('`%s` 的非负度量值，单位由字段名定义。', item.column_name)
                WHEN item.column_name IN ('http_method', 'content_type', 'query_string') THEN
                    format('HTTP 请求审计记录中的 `%s`。', item.column_name)
                WHEN item.column_name IN ('ip_allowlist', 'permissions', 'admin_roles', 'supported_order_types',
                                          'supported_time_in_force') THEN
                    format('授权或能力控制使用的 `%s` 集合。', item.column_name)
                WHEN item.column_name IN ('lease_until') THEN '租约失效时间；超过该时间后其他实例可接管。'
                WHEN item.column_name IN ('maker_instrument_change_id', 'taker_instrument_change_id') THEN
                    format('成交%s侧订单绑定的合约配置版本。', item.column_name)
                WHEN item.column_name IN ('maker_position_side', 'taker_position_side', 'scan_position_side',
                                          'target_position_side') THEN
                    format('`%s` 对应的持仓方向。', item.column_name)
                WHEN item.column_name IN ('target_side') THEN 'ADL 或强平目标用户的买卖方向。'
                WHEN item.column_name IN ('object_key', 'original_filename', 'document_type') THEN
                    format('上传文档在对象存储或原始请求中的 `%s`。', item.column_name)
                WHEN item.column_name IN ('password_hash', 'token_hash', 'secret_ciphertext',
                                          'totp_secret_ciphertext', 'api_key') THEN
                    format('安全凭据 `%s` 的哈希、密文或公开标识；不得存储明文秘密。', item.column_name)
                WHEN item.column_name IN ('participant_role') THEN '本次成交结算参与方角色：maker 或 taker。'
                WHEN item.column_name IN ('period') THEN 'K 线周期代码，例如 1m、5m 或 1h。'
                WHEN item.column_name IN ('sequence_name') THEN '业务序列名称；在所属序列表内唯一。'
                WHEN item.column_name IN ('severity') THEN '风险标签严重级别，用于告警和处置优先级。'
                WHEN item.column_name IN ('chain') THEN '充提资产所在的区块链网络代码。'
                WHEN item.column_name IN ('permission_code', 'role_code', 'rule_code', 'scene_code',
                                          'tag_code', 'tier_code') THEN
                    format('供程序稳定引用的 `%s` 唯一代码。', item.column_name)
                WHEN item.column_name IN ('permission_name', 'role_name', 'rule_name', 'source_name') THEN
                    format('`%s` 的可读名称。', item.column_name)
                WHEN item.column_name IN ('price_precision', 'quantity_precision') THEN
                    format('`%s` 的小数位数。', item.column_name)
                WHEN item.column_name IN ('purpose', 'visibility') THEN
                    format('数据或配置的 `%s` 使用范围。', item.column_name)
                WHEN item.column_name IN ('reservation_account_type', 'source_account_type', 'target_account_type') THEN
                    format('资金预占或划转使用的 `%s`。', item.column_name)
                WHEN item.column_name IN ('reservation_asset') THEN '订单资金预占使用的资产代码。'
                WHEN item.column_name IN ('result', 'result_code') THEN
                    format('命令、调用或投影处理的 `%s`。', item.column_name)
                WHEN item.column_name IN ('source', 'source_partition', 'source_offset', 'source_symbol') THEN
                    format('来源系统定位、去重或回放使用的 `%s`。', item.column_name)
                WHEN item.column_name IN ('to_address') THEN '链上提现的目标地址。'
                WHEN item.column_name IN ('websocket_subscribe_message') THEN '连接外部 WebSocket 后发送的订阅消息。'
                WHEN item.column_name IN ('created_at_epoch_ms', 'updated_at_epoch_ms') THEN
                    format('`%s`，Unix epoch 毫秒。', item.column_name)
                WHEN item.column_name LIKE '%\_id' ESCAPE '\' THEN
                    format('`%s` 对应业务对象的唯一标识。', item.column_name)
                WHEN item.column_name LIKE '%\_units' ESCAPE '\' THEN
                    format('`%s` 的最小精度整数值，禁止使用浮点数。', item.column_name)
                WHEN item.column_name LIKE '%\_ppm' ESCAPE '\' THEN
                    format('`%s` 的 ppm 定点值（一百万分之一）。', item.column_name)
                WHEN item.column_name LIKE '%\_steps' ESCAPE '\' THEN
                    format('`%s` 的离散数量 step。', item.column_name)
                WHEN item.column_name LIKE '%\_ticks' ESCAPE '\' THEN
                    format('`%s` 的离散价格 tick。', item.column_name)
                WHEN item.column_name LIKE '%\_count' ESCAPE '\' THEN
                    format('`%s` 的累计数量或本批次数量。', item.column_name)
                WHEN item.column_name LIKE '%\_sequence' ESCAPE '\' THEN
                    format('`%s` 的单调序列号。', item.column_name)
                WHEN item.column_name LIKE '%\_revision' ESCAPE '\' THEN
                    format('`%s` 的状态修订号。', item.column_name)
                WHEN item.column_name LIKE '%\_at' ESCAPE '\' OR item.column_name LIKE '%\_time' ESCAPE '\' THEN
                    format('`%s` 对应的业务时间。', item.column_name)
                WHEN item.column_name LIKE '%\_enabled' ESCAPE '\' OR item.column_name LIKE 'is\_%' ESCAPE '\' THEN
                    format('`%s` 功能开关或布尔状态。', item.column_name)
                WHEN item.column_name LIKE '%\_status' ESCAPE '\' THEN
                    format('`%s` 业务状态；允许值由所在表约束限定。', item.column_name)
                ELSE format('表 `%s` 的 `%s` 业务属性，存储类型为 %s。',
                            item.table_name, item.column_name, item.data_type)
            END
        END);
        EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                       'public', item.table_name, item.column_name, column_description);
    END LOOP;

    FOR item IN
        SELECT c.relname AS table_name, a.attname AS column_name
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND a.attname = 'product_line'
    LOOP
        constraint_name := left(item.table_name || '_product_line_domain_ck', 63);
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = format('%I.%I', 'public', item.table_name)::regclass
               AND conname = constraint_name
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.%I ADD CONSTRAINT %I CHECK (%I IN (%L,%L,%L,%L,%L,%L))',
                'public', item.table_name, constraint_name, item.column_name,
                'SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION');
        END IF;
    END LOOP;

    FOR item IN
        SELECT c.relname AS table_name, a.attname AS column_name
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND a.atttypid IN ('int2'::regtype, 'int4'::regtype, 'int8'::regtype)
           AND (a.attname IN ('version', 'instrument_change_id')
                OR a.attname ~ '(_sequence|_revision|_count)$'
                OR a.attname IN ('sequence', 'revision', 'attempts', 'cluster_position'))
    LOOP
        constraint_name := left(item.table_name || '_' || item.column_name, 50)
                           || '_' || substr(md5(item.table_name || '.' || item.column_name), 1, 8)
                           || '_ck';
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = format('%I.%I', 'public', item.table_name)::regclass
               AND conname = constraint_name
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.%I ADD CONSTRAINT %I CHECK (%I %s)',
                'public', item.table_name, constraint_name, item.column_name,
                CASE WHEN item.column_name IN ('version', 'instrument_change_id')
                     THEN '> 0' ELSE '>= 0' END);
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum
         WHERE c.relnamespace = 'public'::regnamespace
           AND c.relkind IN ('r', 'p')
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND d.description IS NULL
    ) THEN
        RAISE EXCEPTION 'schema documentation gate failed: uncommented columns remain';
    END IF;

    SELECT string_agg(c.relname || '.' || a.attname, ', ' ORDER BY c.relname, a.attnum)
      INTO generic_columns
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum
     WHERE c.relnamespace = 'public'::regnamespace
       AND c.relkind IN ('r', 'p')
       AND a.attnum > 0
       AND NOT a.attisdropped
       AND d.description LIKE '表 `%` 的 `%` 业务属性，存储类型为 %';
    IF generic_columns IS NOT NULL THEN
        RAISE EXCEPTION 'schema documentation gate failed: generic column descriptions remain: %', generic_columns;
    END IF;

END $$;

-- Apply to the trading database before starting the upgraded providers.
CREATE TABLE IF NOT EXISTS trading_maintenance_task (
    id BIGSERIAL PRIMARY KEY,
    product_line VARCHAR(32) NOT NULL CHECK (product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    request_id UUID NOT NULL,
    instrument_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL DEFAULT 0 CHECK (user_id >= 0),
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('CANCEL','MARKET','LIMIT','SETTLEMENT')),
    price_ticks BIGINT NOT NULL DEFAULT 0 CHECK (price_ticks >= 0),
    reason VARCHAR(1024) NOT NULL,
    admin_user_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','BLOCKED','COMPLETED','RELEASED')),
    phase VARCHAR(32) NOT NULL DEFAULT 'GATE',
    cursor_user_id BIGINT NOT NULL DEFAULT 0,
    round_no INTEGER NOT NULL DEFAULT 0,
    step BIGINT NOT NULL DEFAULT 0,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_line, request_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS trading_maintenance_active_symbol
    ON trading_maintenance_task(product_line,instrument_id) WHERE status <> 'RELEASED';
CREATE INDEX IF NOT EXISTS trading_maintenance_pending
    ON trading_maintenance_task(product_line,updated_at,id) WHERE status = 'RUNNING';
CREATE TABLE IF NOT EXISTS trading_maintenance_action (
    task_id BIGINT NOT NULL REFERENCES trading_maintenance_task(id),
    action_key VARCHAR(128) NOT NULL,
    request_json TEXT NOT NULL,
    result_json TEXT,
    completed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(task_id,action_key)
);
COMMENT ON TABLE trading_maintenance_task IS '交易维护任务；Core 状态决定业务完成，数据库保存调度进度与审批请求身份';
COMMENT ON COLUMN trading_maintenance_task.id IS '维护任务唯一标识，同时作为 Core 币对维护所有者';
COMMENT ON COLUMN trading_maintenance_task.product_line IS '任务所属产品线，与单产品 Core 严格一致';
COMMENT ON COLUMN trading_maintenance_task.request_id IS '运营请求幂等 UUID；同一身份不能改变范围或参数';
COMMENT ON COLUMN trading_maintenance_task.instrument_id IS '维护的精确币对；维护门控作用于整个币对';
COMMENT ON COLUMN trading_maintenance_task.user_id IS '待处理用户，零表示该币对全部用户';
COMMENT ON COLUMN trading_maintenance_task.mode IS '撤单、IOC 市价或限价平仓、固定价格清退';
COMMENT ON COLUMN trading_maintenance_task.price_ticks IS '经审批的价格整数 ticks；期权清退为标的结算价';
COMMENT ON COLUMN trading_maintenance_task.reason IS '运营提交的维护原因或关联工单';
COMMENT ON COLUMN trading_maintenance_task.admin_user_id IS '网关认证并审计的运营人员标识';
COMMENT ON COLUMN trading_maintenance_task.status IS '运行、阻塞、Core 已确认完成、已恢复交易';
COMMENT ON COLUMN trading_maintenance_task.phase IS '维护门控、撤触发单、撤普通单、平仓、结算、终态核对阶段';
COMMENT ON COLUMN trading_maintenance_task.cursor_user_id IS '已持久化平仓计划的最后一位持仓用户';
COMMENT ON COLUMN trading_maintenance_task.round_no IS '运营重试剩余持仓的轮次，用于生成新的平仓身份';
COMMENT ON COLUMN trading_maintenance_task.step IS '已提交任务状态迁移次数，用于结算命令身份';
COMMENT ON COLUMN trading_maintenance_task.error IS '未完成原因，包括未知命令结果、流动性或保险资金不足';
COMMENT ON COLUMN trading_maintenance_task.created_at IS '维护请求持久化时间';
COMMENT ON COLUMN trading_maintenance_task.updated_at IS '任务最近状态更新时间';
COMMENT ON TABLE trading_maintenance_action IS '维护命令意图与实际结果；发往 Core 前必须提交意图事务';
COMMENT ON COLUMN trading_maintenance_action.task_id IS '所属维护任务标识';
COMMENT ON COLUMN trading_maintenance_action.action_key IS '任务内命令幂等身份，重启与超时不改变';
COMMENT ON COLUMN trading_maintenance_action.request_json IS '不可变命令参数，价格、数量、身份均为整数单位';
COMMENT ON COLUMN trading_maintenance_action.result_json IS 'Core 实际命令结果或查询到的订单终态';
COMMENT ON COLUMN trading_maintenance_action.completed IS '该步命令结果已确认；不代表整个任务完成';
COMMENT ON COLUMN trading_maintenance_action.created_at IS '命令意图提交时间';
COMMENT ON COLUMN trading_maintenance_action.updated_at IS '命令结果最后核对时间';

-- 登录二次验证：联系方式验证状态与一次性挑战由数据库持有。
CREATE TABLE IF NOT EXISTS gateway_login_factors (
 user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
 method TEXT NOT NULL CHECK (method IN ('EMAIL','PHONE')),
 destination TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 verified_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, method)
);
CREATE TABLE IF NOT EXISTS gateway_login_challenges (
 user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
 purpose TEXT NOT NULL CHECK (purpose IN ('LOGIN','EMAIL','PHONE','TOTP')),
 token_hash TEXT NOT NULL UNIQUE,
 fingerprint TEXT NOT NULL,
 methods TEXT NOT NULL,
 email_hash TEXT,
 phone_hash TEXT,
 destination TEXT,
 expires_at TIMESTAMPTZ NOT NULL,
 target_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 consumed BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INTEGER NOT NULL DEFAULT 0,
 issued INTEGER NOT NULL DEFAULT 0,
 window_start TIMESTAMPTZ NOT NULL,
 issued_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(user_id,purpose)
);
ALTER TABLE gateway_user_mfa ADD COLUMN IF NOT EXISTS last_login_step BIGINT NOT NULL DEFAULT -1;

-- Permanent identity and accounting assets cannot be rewritten by a configuration update.
CREATE FUNCTION reject_instrument_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.instrument_id IS DISTINCT FROM NEW.instrument_id
       OR OLD.contract_type IS DISTINCT FROM NEW.contract_type
       OR OLD.base_asset_id IS DISTINCT FROM NEW.base_asset_id
       OR OLD.quote_asset_id IS DISTINCT FROM NEW.quote_asset_id
       OR OLD.settle_asset_id IS DISTINCT FROM NEW.settle_asset_id
       OR OLD.contract_value_asset_id IS DISTINCT FROM NEW.contract_value_asset_id
       OR OLD.underlying_instrument_id IS DISTINCT FROM NEW.underlying_instrument_id
       OR OLD.underlying_product_line IS DISTINCT FROM NEW.underlying_product_line THEN
        RAISE EXCEPTION 'instrument identity and product line are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER instruments_identity_immutable BEFORE UPDATE ON instruments
FOR EACH ROW EXECUTE FUNCTION reject_instrument_identity_change();
COMMENT ON COLUMN instruments.instrument_id IS '服务端创建时分配的永久币对 ID；名称、配置及状态变化不得重建或改变 ID';

ALTER TABLE instruments ADD FOREIGN KEY (underlying_product_line, underlying_instrument_id) REFERENCES instruments(product_line,instrument_id);

ALTER TABLE instrument_lifecycle_drain_acks ADD FOREIGN KEY(product_line,instrument_id,instrument_change_id) REFERENCES instrument_change_log(product_line,instrument_id,change_id);

COMMIT;

-- 生命周期业务设置仅由管理后台维护；不同产品线独立，更新使用版本比较。
CREATE TABLE IF NOT EXISTS lifecycle_business_settings (
    product_line VARCHAR(32) PRIMARY KEY CHECK (product_line IN ('LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 后台为价格业务配置唯一入口；连接、线程和保留策略属于部署参数。
CREATE TABLE IF NOT EXISTS price_business_settings (
    product_line VARCHAR(32) PRIMARY KEY CHECK (product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE price_mark_ticks ALTER COLUMN next_funding_time DROP NOT NULL;

CREATE TABLE IF NOT EXISTS order_business_settings (
    product_line VARCHAR(32) PRIMARY KEY CHECK (product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings)='object'),
    version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
    updated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK(length(trim(reason))>0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS gateway_kyc_provider_events (
    provider TEXT NOT NULL,
    event_key TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_key)
);

CREATE INDEX IF NOT EXISTS gateway_kyc_provider_events_received_idx
    ON gateway_kyc_provider_events (received_at DESC);

CREATE TABLE IF NOT EXISTS gateway_countries (code CHAR(2) PRIMARY KEY, flag TEXT NOT NULL, names JSONB NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE);

INSERT INTO gateway_countries(code,flag,names) VALUES ('AD','🇦🇩','{"en":"Andorra","zh":"安道尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AE','🇦🇪','{"en":"United Arab Emirates","zh":"阿拉伯联合酋长国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AF','🇦🇫','{"en":"Afghanistan","zh":"阿富汗"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AG','🇦🇬','{"en":"Antigua & Barbuda","zh":"安提瓜和巴布达"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AI','🇦🇮','{"en":"Anguilla","zh":"安圭拉"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AL','🇦🇱','{"en":"Albania","zh":"阿尔巴尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AM','🇦🇲','{"en":"Armenia","zh":"亚美尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AO','🇦🇴','{"en":"Angola","zh":"安哥拉"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AQ','🇦🇶','{"en":"Antarctica","zh":"南极洲"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AR','🇦🇷','{"en":"Argentina","zh":"阿根廷"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AS','🇦🇸','{"en":"American Samoa","zh":"美属萨摩亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AT','🇦🇹','{"en":"Austria","zh":"奥地利"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AU','🇦🇺','{"en":"Australia","zh":"澳大利亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AW','🇦🇼','{"en":"Aruba","zh":"阿鲁巴"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AX','🇦🇽','{"en":"Åland Islands","zh":"奥兰群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('AZ','🇦🇿','{"en":"Azerbaijan","zh":"阿塞拜疆"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BA','🇧🇦','{"en":"Bosnia & Herzegovina","zh":"波斯尼亚和黑塞哥维那"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BB','🇧🇧','{"en":"Barbados","zh":"巴巴多斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BD','🇧🇩','{"en":"Bangladesh","zh":"孟加拉国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BE','🇧🇪','{"en":"Belgium","zh":"比利时"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BF','🇧🇫','{"en":"Burkina Faso","zh":"布基纳法索"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BG','🇧🇬','{"en":"Bulgaria","zh":"保加利亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BH','🇧🇭','{"en":"Bahrain","zh":"巴林"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BI','🇧🇮','{"en":"Burundi","zh":"布隆迪"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BJ','🇧🇯','{"en":"Benin","zh":"贝宁"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BL','🇧🇱','{"en":"St. Barthélemy","zh":"圣巴泰勒米"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BM','🇧🇲','{"en":"Bermuda","zh":"百慕大"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BN','🇧🇳','{"en":"Brunei","zh":"文莱"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BO','🇧🇴','{"en":"Bolivia","zh":"玻利维亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BQ','🇧🇶','{"en":"Caribbean Netherlands","zh":"荷属加勒比区"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BR','🇧🇷','{"en":"Brazil","zh":"巴西"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BS','🇧🇸','{"en":"Bahamas","zh":"巴哈马"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BT','🇧🇹','{"en":"Bhutan","zh":"不丹"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BV','🇧🇻','{"en":"Bouvet Island","zh":"布韦岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BW','🇧🇼','{"en":"Botswana","zh":"博茨瓦纳"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BY','🇧🇾','{"en":"Belarus","zh":"白俄罗斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('BZ','🇧🇿','{"en":"Belize","zh":"伯利兹"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CA','🇨🇦','{"en":"Canada","zh":"加拿大"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CC','🇨🇨','{"en":"Cocos (Keeling) Islands","zh":"科科斯（基林）群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CD','🇨🇩','{"en":"Congo - Kinshasa","zh":"刚果（金）"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CF','🇨🇫','{"en":"Central African Republic","zh":"中非共和国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CG','🇨🇬','{"en":"Congo - Brazzaville","zh":"刚果（布）"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CH','🇨🇭','{"en":"Switzerland","zh":"瑞士"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CI','🇨🇮','{"en":"Côte d’Ivoire","zh":"科特迪瓦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CK','🇨🇰','{"en":"Cook Islands","zh":"库克群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CL','🇨🇱','{"en":"Chile","zh":"智利"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CM','🇨🇲','{"en":"Cameroon","zh":"喀麦隆"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CN','🇨🇳','{"en":"China","zh":"中国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CO','🇨🇴','{"en":"Colombia","zh":"哥伦比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CR','🇨🇷','{"en":"Costa Rica","zh":"哥斯达黎加"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CU','🇨🇺','{"en":"Cuba","zh":"古巴"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CV','🇨🇻','{"en":"Cape Verde","zh":"佛得角"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CW','🇨🇼','{"en":"Curaçao","zh":"库拉索"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CX','🇨🇽','{"en":"Christmas Island","zh":"圣诞岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CY','🇨🇾','{"en":"Cyprus","zh":"塞浦路斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('CZ','🇨🇿','{"en":"Czechia","zh":"捷克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DE','🇩🇪','{"en":"Germany","zh":"德国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DJ','🇩🇯','{"en":"Djibouti","zh":"吉布提"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DK','🇩🇰','{"en":"Denmark","zh":"丹麦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DM','🇩🇲','{"en":"Dominica","zh":"多米尼克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DO','🇩🇴','{"en":"Dominican Republic","zh":"多米尼加共和国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('DZ','🇩🇿','{"en":"Algeria","zh":"阿尔及利亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('EC','🇪🇨','{"en":"Ecuador","zh":"厄瓜多尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('EE','🇪🇪','{"en":"Estonia","zh":"爱沙尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('EG','🇪🇬','{"en":"Egypt","zh":"埃及"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('EH','🇪🇭','{"en":"Western Sahara","zh":"西撒哈拉"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ER','🇪🇷','{"en":"Eritrea","zh":"厄立特里亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ES','🇪🇸','{"en":"Spain","zh":"西班牙"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ET','🇪🇹','{"en":"Ethiopia","zh":"埃塞俄比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FI','🇫🇮','{"en":"Finland","zh":"芬兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FJ','🇫🇯','{"en":"Fiji","zh":"斐济"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FK','🇫🇰','{"en":"Falkland Islands","zh":"福克兰群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FM','🇫🇲','{"en":"Micronesia","zh":"密克罗尼西亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FO','🇫🇴','{"en":"Faroe Islands","zh":"法罗群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('FR','🇫🇷','{"en":"France","zh":"法国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GA','🇬🇦','{"en":"Gabon","zh":"加蓬"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GB','🇬🇧','{"en":"United Kingdom","zh":"英国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GD','🇬🇩','{"en":"Grenada","zh":"格林纳达"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GE','🇬🇪','{"en":"Georgia","zh":"格鲁吉亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GF','🇬🇫','{"en":"French Guiana","zh":"法属圭亚那"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GG','🇬🇬','{"en":"Guernsey","zh":"根西岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GH','🇬🇭','{"en":"Ghana","zh":"加纳"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GI','🇬🇮','{"en":"Gibraltar","zh":"直布罗陀"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GL','🇬🇱','{"en":"Greenland","zh":"格陵兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GM','🇬🇲','{"en":"Gambia","zh":"冈比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GN','🇬🇳','{"en":"Guinea","zh":"几内亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GP','🇬🇵','{"en":"Guadeloupe","zh":"瓜德罗普"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GQ','🇬🇶','{"en":"Equatorial Guinea","zh":"赤道几内亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GR','🇬🇷','{"en":"Greece","zh":"希腊"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GS','🇬🇸','{"en":"South Georgia & South Sandwich Islands","zh":"南乔治亚和南桑威奇群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GT','🇬🇹','{"en":"Guatemala","zh":"危地马拉"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GU','🇬🇺','{"en":"Guam","zh":"关岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GW','🇬🇼','{"en":"Guinea-Bissau","zh":"几内亚比绍"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('GY','🇬🇾','{"en":"Guyana","zh":"圭亚那"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HK','🇭🇰','{"en":"Hong Kong SAR China","zh":"中国香港特别行政区"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HM','🇭🇲','{"en":"Heard & McDonald Islands","zh":"赫德岛和麦克唐纳群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HN','🇭🇳','{"en":"Honduras","zh":"洪都拉斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HR','🇭🇷','{"en":"Croatia","zh":"克罗地亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HT','🇭🇹','{"en":"Haiti","zh":"海地"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('HU','🇭🇺','{"en":"Hungary","zh":"匈牙利"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ID','🇮🇩','{"en":"Indonesia","zh":"印度尼西亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IE','🇮🇪','{"en":"Ireland","zh":"爱尔兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IL','🇮🇱','{"en":"Israel","zh":"以色列"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IM','🇮🇲','{"en":"Isle of Man","zh":"马恩岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IN','🇮🇳','{"en":"India","zh":"印度"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IO','🇮🇴','{"en":"British Indian Ocean Territory","zh":"英属印度洋领地"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IQ','🇮🇶','{"en":"Iraq","zh":"伊拉克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IR','🇮🇷','{"en":"Iran","zh":"伊朗"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IS','🇮🇸','{"en":"Iceland","zh":"冰岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('IT','🇮🇹','{"en":"Italy","zh":"意大利"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('JE','🇯🇪','{"en":"Jersey","zh":"泽西岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('JM','🇯🇲','{"en":"Jamaica","zh":"牙买加"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('JO','🇯🇴','{"en":"Jordan","zh":"约旦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('JP','🇯🇵','{"en":"Japan","zh":"日本"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KE','🇰🇪','{"en":"Kenya","zh":"肯尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KG','🇰🇬','{"en":"Kyrgyzstan","zh":"吉尔吉斯斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KH','🇰🇭','{"en":"Cambodia","zh":"柬埔寨"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KI','🇰🇮','{"en":"Kiribati","zh":"基里巴斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KM','🇰🇲','{"en":"Comoros","zh":"科摩罗"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KN','🇰🇳','{"en":"St. Kitts & Nevis","zh":"圣基茨和尼维斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KP','🇰🇵','{"en":"North Korea","zh":"朝鲜"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KR','🇰🇷','{"en":"South Korea","zh":"韩国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KW','🇰🇼','{"en":"Kuwait","zh":"科威特"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KY','🇰🇾','{"en":"Cayman Islands","zh":"开曼群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('KZ','🇰🇿','{"en":"Kazakhstan","zh":"哈萨克斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LA','🇱🇦','{"en":"Laos","zh":"老挝"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LB','🇱🇧','{"en":"Lebanon","zh":"黎巴嫩"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LC','🇱🇨','{"en":"St. Lucia","zh":"圣卢西亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LI','🇱🇮','{"en":"Liechtenstein","zh":"列支敦士登"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LK','🇱🇰','{"en":"Sri Lanka","zh":"斯里兰卡"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LR','🇱🇷','{"en":"Liberia","zh":"利比里亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LS','🇱🇸','{"en":"Lesotho","zh":"莱索托"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LT','🇱🇹','{"en":"Lithuania","zh":"立陶宛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LU','🇱🇺','{"en":"Luxembourg","zh":"卢森堡"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LV','🇱🇻','{"en":"Latvia","zh":"拉脱维亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('LY','🇱🇾','{"en":"Libya","zh":"利比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MA','🇲🇦','{"en":"Morocco","zh":"摩洛哥"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MC','🇲🇨','{"en":"Monaco","zh":"摩纳哥"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MD','🇲🇩','{"en":"Moldova","zh":"摩尔多瓦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ME','🇲🇪','{"en":"Montenegro","zh":"黑山"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MF','🇲🇫','{"en":"St. Martin","zh":"法属圣马丁"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MG','🇲🇬','{"en":"Madagascar","zh":"马达加斯加"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MH','🇲🇭','{"en":"Marshall Islands","zh":"马绍尔群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MK','🇲🇰','{"en":"North Macedonia","zh":"北马其顿"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ML','🇲🇱','{"en":"Mali","zh":"马里"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MM','🇲🇲','{"en":"Myanmar (Burma)","zh":"缅甸"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MN','🇲🇳','{"en":"Mongolia","zh":"蒙古"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MO','🇲🇴','{"en":"Macao SAR China","zh":"中国澳门特别行政区"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MP','🇲🇵','{"en":"Northern Mariana Islands","zh":"北马里亚纳群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MQ','🇲🇶','{"en":"Martinique","zh":"马提尼克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MR','🇲🇷','{"en":"Mauritania","zh":"毛里塔尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MS','🇲🇸','{"en":"Montserrat","zh":"蒙特塞拉特"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MT','🇲🇹','{"en":"Malta","zh":"马耳他"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MU','🇲🇺','{"en":"Mauritius","zh":"毛里求斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MV','🇲🇻','{"en":"Maldives","zh":"马尔代夫"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MW','🇲🇼','{"en":"Malawi","zh":"马拉维"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MX','🇲🇽','{"en":"Mexico","zh":"墨西哥"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MY','🇲🇾','{"en":"Malaysia","zh":"马来西亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('MZ','🇲🇿','{"en":"Mozambique","zh":"莫桑比克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NA','🇳🇦','{"en":"Namibia","zh":"纳米比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NC','🇳🇨','{"en":"New Caledonia","zh":"新喀里多尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NE','🇳🇪','{"en":"Niger","zh":"尼日尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NF','🇳🇫','{"en":"Norfolk Island","zh":"诺福克岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NG','🇳🇬','{"en":"Nigeria","zh":"尼日利亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NI','🇳🇮','{"en":"Nicaragua","zh":"尼加拉瓜"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NL','🇳🇱','{"en":"Netherlands","zh":"荷兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NO','🇳🇴','{"en":"Norway","zh":"挪威"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NP','🇳🇵','{"en":"Nepal","zh":"尼泊尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NR','🇳🇷','{"en":"Nauru","zh":"瑙鲁"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NU','🇳🇺','{"en":"Niue","zh":"纽埃"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('NZ','🇳🇿','{"en":"New Zealand","zh":"新西兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('OM','🇴🇲','{"en":"Oman","zh":"阿曼"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PA','🇵🇦','{"en":"Panama","zh":"巴拿马"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PE','🇵🇪','{"en":"Peru","zh":"秘鲁"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PF','🇵🇫','{"en":"French Polynesia","zh":"法属波利尼西亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PG','🇵🇬','{"en":"Papua New Guinea","zh":"巴布亚新几内亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PH','🇵🇭','{"en":"Philippines","zh":"菲律宾"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PK','🇵🇰','{"en":"Pakistan","zh":"巴基斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PL','🇵🇱','{"en":"Poland","zh":"波兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PM','🇵🇲','{"en":"St. Pierre & Miquelon","zh":"圣皮埃尔和密克隆群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PN','🇵🇳','{"en":"Pitcairn Islands","zh":"皮特凯恩群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PR','🇵🇷','{"en":"Puerto Rico","zh":"波多黎各"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PS','🇵🇸','{"en":"Palestinian Territories","zh":"巴勒斯坦领土"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PT','🇵🇹','{"en":"Portugal","zh":"葡萄牙"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PW','🇵🇼','{"en":"Palau","zh":"帕劳"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('PY','🇵🇾','{"en":"Paraguay","zh":"巴拉圭"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('QA','🇶🇦','{"en":"Qatar","zh":"卡塔尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('RE','🇷🇪','{"en":"Réunion","zh":"留尼汪"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('RO','🇷🇴','{"en":"Romania","zh":"罗马尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('RS','🇷🇸','{"en":"Serbia","zh":"塞尔维亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('RU','🇷🇺','{"en":"Russia","zh":"俄罗斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('RW','🇷🇼','{"en":"Rwanda","zh":"卢旺达"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SA','🇸🇦','{"en":"Saudi Arabia","zh":"沙特阿拉伯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SB','🇸🇧','{"en":"Solomon Islands","zh":"所罗门群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SC','🇸🇨','{"en":"Seychelles","zh":"塞舌尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SD','🇸🇩','{"en":"Sudan","zh":"苏丹"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SE','🇸🇪','{"en":"Sweden","zh":"瑞典"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SG','🇸🇬','{"en":"Singapore","zh":"新加坡"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SH','🇸🇭','{"en":"St. Helena","zh":"圣赫勒拿"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SI','🇸🇮','{"en":"Slovenia","zh":"斯洛文尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SJ','🇸🇯','{"en":"Svalbard & Jan Mayen","zh":"斯瓦尔巴和扬马延"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SK','🇸🇰','{"en":"Slovakia","zh":"斯洛伐克"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SL','🇸🇱','{"en":"Sierra Leone","zh":"塞拉利昂"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SM','🇸🇲','{"en":"San Marino","zh":"圣马力诺"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SN','🇸🇳','{"en":"Senegal","zh":"塞内加尔"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SO','🇸🇴','{"en":"Somalia","zh":"索马里"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SR','🇸🇷','{"en":"Suriname","zh":"苏里南"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SS','🇸🇸','{"en":"South Sudan","zh":"南苏丹"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ST','🇸🇹','{"en":"São Tomé & Príncipe","zh":"圣多美和普林西比"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SV','🇸🇻','{"en":"El Salvador","zh":"萨尔瓦多"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SX','🇸🇽','{"en":"Sint Maarten","zh":"荷属圣马丁"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SY','🇸🇾','{"en":"Syria","zh":"叙利亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('SZ','🇸🇿','{"en":"Eswatini","zh":"斯威士兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TC','🇹🇨','{"en":"Turks & Caicos Islands","zh":"特克斯和凯科斯群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TD','🇹🇩','{"en":"Chad","zh":"乍得"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TF','🇹🇫','{"en":"French Southern Territories","zh":"法属南部领地"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TG','🇹🇬','{"en":"Togo","zh":"多哥"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TH','🇹🇭','{"en":"Thailand","zh":"泰国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TJ','🇹🇯','{"en":"Tajikistan","zh":"塔吉克斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TK','🇹🇰','{"en":"Tokelau","zh":"托克劳"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TL','🇹🇱','{"en":"Timor-Leste","zh":"东帝汶"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TM','🇹🇲','{"en":"Turkmenistan","zh":"土库曼斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TN','🇹🇳','{"en":"Tunisia","zh":"突尼斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TO','🇹🇴','{"en":"Tonga","zh":"汤加"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TR','🇹🇷','{"en":"Türkiye","zh":"土耳其"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TT','🇹🇹','{"en":"Trinidad & Tobago","zh":"特立尼达和多巴哥"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TV','🇹🇻','{"en":"Tuvalu","zh":"图瓦卢"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TW','🇹🇼','{"en":"Taiwan","zh":"台湾"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('TZ','🇹🇿','{"en":"Tanzania","zh":"坦桑尼亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('UA','🇺🇦','{"en":"Ukraine","zh":"乌克兰"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('UG','🇺🇬','{"en":"Uganda","zh":"乌干达"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('UM','🇺🇲','{"en":"U.S. Outlying Islands","zh":"美国本土外小岛屿"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('US','🇺🇸','{"en":"United States","zh":"美国"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('UY','🇺🇾','{"en":"Uruguay","zh":"乌拉圭"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('UZ','🇺🇿','{"en":"Uzbekistan","zh":"乌兹别克斯坦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VA','🇻🇦','{"en":"Vatican City","zh":"梵蒂冈"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VC','🇻🇨','{"en":"St. Vincent & Grenadines","zh":"圣文森特和格林纳丁斯"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VE','🇻🇪','{"en":"Venezuela","zh":"委内瑞拉"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VG','🇻🇬','{"en":"British Virgin Islands","zh":"英属维尔京群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VI','🇻🇮','{"en":"U.S. Virgin Islands","zh":"美属维尔京群岛"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VN','🇻🇳','{"en":"Vietnam","zh":"越南"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('VU','🇻🇺','{"en":"Vanuatu","zh":"瓦努阿图"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('WF','🇼🇫','{"en":"Wallis & Futuna","zh":"瓦利斯和富图纳"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('WS','🇼🇸','{"en":"Samoa","zh":"萨摩亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('YE','🇾🇪','{"en":"Yemen","zh":"也门"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('YT','🇾🇹','{"en":"Mayotte","zh":"马约特"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ZA','🇿🇦','{"en":"South Africa","zh":"南非"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ZM','🇿🇲','{"en":"Zambia","zh":"赞比亚"}'::jsonb) ON CONFLICT (code) DO NOTHING;
INSERT INTO gateway_countries(code,flag,names) VALUES ('ZW','🇿🇼','{"en":"Zimbabwe","zh":"津巴布韦"}'::jsonb) ON CONFLICT (code) DO NOTHING;
