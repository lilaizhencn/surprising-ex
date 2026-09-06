-- Apply to the trading database before starting the upgraded providers.
CREATE TABLE IF NOT EXISTS trading_maintenance_task (
    id BIGSERIAL PRIMARY KEY,
    product_line VARCHAR(32) NOT NULL CHECK (product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    request_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
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
    ON trading_maintenance_task(product_line,symbol) WHERE status <> 'RELEASED';
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
COMMENT ON COLUMN trading_maintenance_task.symbol IS '维护的精确币对；维护门控作用于整个币对';
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
