-- 仅扩展业务配置结构；不覆盖现有参数，不删除资金或交易历史。
-- 上线前必须将当前正在运行的做市设置迁入新表并核对，再发布新版本。
BEGIN;
ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_status_check;
ALTER TABLE instruments ADD CONSTRAINT instruments_status_check CHECK
  (status IN ('PRE_TRADING','TRADING','HALT','SETTLING','CLOSED','DRAFT'));
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

COMMIT;
