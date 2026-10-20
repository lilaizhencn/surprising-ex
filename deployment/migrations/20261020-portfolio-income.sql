-- Read-only financial history produced only from a committed Archive replay.
-- A cutoff row certifies its complete user set, including users absent at that prefix.
CREATE TABLE IF NOT EXISTS core_portfolio_cutoff (
    product_line varchar(32) NOT NULL,
    cutoff_epoch_ms bigint NOT NULL CHECK (cutoff_epoch_ms > 0),
    cluster_position bigint NOT NULL CHECK (cluster_position >= 0),
    state_sequence bigint NOT NULL CHECK (state_sequence >= 0),
    source_timestamp bigint NOT NULL CHECK (source_timestamp >= 0 AND source_timestamp <= cutoff_epoch_ms),
    flow_covered_from_epoch_ms bigint NOT NULL CHECK (flow_covered_from_epoch_ms >= 0),
    day_opening boolean NOT NULL,
    PRIMARY KEY (product_line, cutoff_epoch_ms)
);

CREATE TABLE IF NOT EXISTS core_portfolio_equity (
    product_line varchar(32) NOT NULL,
    cutoff_epoch_ms bigint NOT NULL,
    user_id bigint NOT NULL CHECK (user_id > 0),
    complete boolean NOT NULL,
    net_asset_units jsonb NOT NULL CHECK (jsonb_typeof(net_asset_units) = 'object'),
    PRIMARY KEY (product_line, cutoff_epoch_ms, user_id),
    FOREIGN KEY (product_line, cutoff_epoch_ms)
        REFERENCES core_portfolio_cutoff(product_line, cutoff_epoch_ms) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS core_portfolio_cash_flow (
    product_line varchar(32) NOT NULL,
    command_id uuid NOT NULL,
    user_id bigint NOT NULL CHECK (user_id > 0),
    cluster_position bigint NOT NULL CHECK (cluster_position > 0),
    occurred_at_epoch_ms bigint NOT NULL CHECK (occurred_at_epoch_ms > 0),
    asset varchar(20) NOT NULL CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    amount_units numeric(40,0) NOT NULL CHECK (amount_units <> 0),
    transfer_id bigint,
    source_product_line varchar(32),
    target_product_line varchar(32),
    source_user_id bigint,
    target_user_id bigint,
    PRIMARY KEY (product_line, command_id, user_id),
    CHECK ((transfer_id IS NULL AND source_product_line IS NULL AND target_product_line IS NULL
            AND source_user_id IS NULL AND target_user_id IS NULL)
        OR (transfer_id IS NOT NULL AND transfer_id > 0
            AND source_product_line IS NOT NULL AND target_product_line IS NOT NULL
            AND source_user_id IS NOT NULL AND target_user_id IS NOT NULL
            AND source_user_id > 0 AND target_user_id > 0))
);
CREATE INDEX IF NOT EXISTS core_portfolio_cash_flow_user_position
    ON core_portfolio_cash_flow (user_id, product_line, cluster_position);
CREATE INDEX IF NOT EXISTS core_portfolio_cash_flow_adjustment_retention
    ON core_portfolio_cash_flow (product_line, occurred_at_epoch_ms)
    WHERE transfer_id IS NULL;
CREATE INDEX IF NOT EXISTS core_portfolio_cash_flow_transfer
    ON core_portfolio_cash_flow (transfer_id, product_line, cluster_position)
    WHERE transfer_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS core_portfolio_projection_start (
    product_line varchar(32) PRIMARY KEY,
    cluster_position bigint NOT NULL CHECK (cluster_position >= 0),
    occurred_at_epoch_ms bigint NOT NULL CHECK (occurred_at_epoch_ms >= 0),
    ready boolean NOT NULL DEFAULT false
);

COMMENT ON TABLE core_portfolio_cutoff IS '已提交回放的完整用户权益切点凭据；不是在线可用余额事实源';
COMMENT ON COLUMN core_portfolio_cutoff.product_line IS '隔离的交易产品线';
COMMENT ON COLUMN core_portfolio_cutoff.cutoff_epoch_ms IS '估值切点的毫秒时间，切点前的命令均已回放';
COMMENT ON COLUMN core_portfolio_cutoff.cluster_position IS '切点前已读取的完整 Archive 日志位置';
COMMENT ON COLUMN core_portfolio_cutoff.state_sequence IS 'Core 状态发布序号，用于核对成交投影进度，不等同于日志位置';
COMMENT ON COLUMN core_portfolio_cutoff.source_timestamp IS '所读取 Core 状态最后一条命令的毫秒时间';
COMMENT ON COLUMN core_portfolio_cutoff.flow_covered_from_epoch_ms IS '连续资金流回放的起始时间';
COMMENT ON COLUMN core_portfolio_cutoff.day_opening IS '是否为交易所业务日的期初切点';
COMMENT ON TABLE core_portfolio_equity IS '切点的逐用户逐币种净权益异步分析投影';
COMMENT ON COLUMN core_portfolio_equity.product_line IS '隔离的交易产品线';
COMMENT ON COLUMN core_portfolio_equity.cutoff_epoch_ms IS '完整权益切点的毫秒时间';
COMMENT ON COLUMN core_portfolio_equity.user_id IS '权益所属用户';
COMMENT ON COLUMN core_portfolio_equity.complete IS '持仓价格和在途状态是否允许完整估值';
COMMENT ON COLUMN core_portfolio_equity.net_asset_units IS '币种到净权益最小单位整数字符串的映射；含持仓市值或浮盈亏';
COMMENT ON TABLE core_portfolio_cash_flow IS '已提交命令实际造成的现金变动；用于剔除本金流入流出并核对内部划转';
COMMENT ON COLUMN core_portfolio_cash_flow.product_line IS '发生现金变动的产品线';
COMMENT ON COLUMN core_portfolio_cash_flow.command_id IS '已提交命令的原始 UUID 幂等身份';
COMMENT ON COLUMN core_portfolio_cash_flow.user_id IS '实际发生现金变动的用户';
COMMENT ON COLUMN core_portfolio_cash_flow.cluster_position IS '命令已提交的 Archive 日志位置';
COMMENT ON COLUMN core_portfolio_cash_flow.occurred_at_epoch_ms IS '集群确定的资金变动毫秒时间';
COMMENT ON COLUMN core_portfolio_cash_flow.asset IS '不可变账务币种代码';
COMMENT ON COLUMN core_portfolio_cash_flow.amount_units IS '带符号的现金净变动最小单位整数';
COMMENT ON COLUMN core_portfolio_cash_flow.transfer_id IS '原始划转身份；普通调账为空';
COMMENT ON COLUMN core_portfolio_cash_flow.source_product_line IS '划转来源产品线';
COMMENT ON COLUMN core_portfolio_cash_flow.target_product_line IS '划转目标产品线';
COMMENT ON COLUMN core_portfolio_cash_flow.source_user_id IS '划转来源用户';
COMMENT ON COLUMN core_portfolio_cash_flow.target_user_id IS '划转接收用户';
COMMENT ON TABLE core_portfolio_projection_start IS '资金历史连续性起点和重建验收状态';
COMMENT ON COLUMN core_portfolio_projection_start.product_line IS '隔离的交易产品线';
COMMENT ON COLUMN core_portfolio_projection_start.cluster_position IS '资金历史连续性起始日志位置';
COMMENT ON COLUMN core_portfolio_projection_start.occurred_at_epoch_ms IS '资金历史连续性起始毫秒时间';
COMMENT ON COLUMN core_portfolio_projection_start.ready IS '是否已从日志起点重建至被接续的主检查点';
