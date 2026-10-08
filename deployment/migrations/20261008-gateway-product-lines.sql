-- 已有环境只增加接入设置，不从进程参数推断业务状态；上线前在后台启用实际运行的产品。
CREATE TABLE IF NOT EXISTS gateway_product_lines (
    product_line TEXT PRIMARY KEY CHECK (product_line IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    enabled BOOLEAN NOT NULL DEFAULT false,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by TEXT NOT NULL DEFAULT 'SYSTEM',
    reason TEXT NOT NULL DEFAULT '等待管理员启用接入' CHECK (length(reason) BETWEEN 1 AND 1000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE gateway_product_lines IS '后台维护的 Gateway 产品线接入设置；接入启用后保留账户连接，交易下线由合约状态控制';
COMMENT ON COLUMN gateway_product_lines.product_line IS '独立交易核心对应的产品线';
COMMENT ON COLUMN gateway_product_lines.enabled IS '管理员是否启用接入；新数据库默认关闭';
COMMENT ON COLUMN gateway_product_lines.version IS '乐观锁版本，防止并发修改覆盖';
COMMENT ON COLUMN gateway_product_lines.updated_by IS '最近修改管理员 ID';
COMMENT ON COLUMN gateway_product_lines.reason IS '最近修改原因';
COMMENT ON COLUMN gateway_product_lines.updated_at IS '最近修改时间';
INSERT INTO gateway_product_lines(product_line)
VALUES ('SPOT'),('LINEAR_PERPETUAL'),('INVERSE_PERPETUAL'),('LINEAR_DELIVERY'),('INVERSE_DELIVERY'),('OPTION')
ON CONFLICT DO NOTHING;
