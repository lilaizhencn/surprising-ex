-- 期权没有资金费周期；完整报价输入仍保存在 calculation_inputs。
ALTER TABLE price_mark_ticks ALTER COLUMN next_funding_time DROP NOT NULL;
