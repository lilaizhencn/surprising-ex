BEGIN;
-- 生命周期业务设置仅由管理后台维护；不同产品线独立，更新使用版本比较。
CREATE TABLE IF NOT EXISTS lifecycle_business_settings (
    product_line VARCHAR(32) PRIMARY KEY CHECK (product_line IN ('LINEAR_PERPETUAL','INVERSE_PERPETUAL','LINEAR_DELIVERY','INVERSE_DELIVERY','OPTION')),
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMIT;
