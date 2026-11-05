-- Telegram Bot 用户安全确认级别与交易密码配置
CREATE TABLE IF NOT EXISTS gateway_user_telegram_security_settings (
    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    security_mode VARCHAR(32) NOT NULL DEFAULT 'CONFIRM_ONLY', -- CONFIRM_ONLY, TRADE_PASSWORD, OTP_VERIFY
    trade_pin_hash TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gateway_user_telegram_sec_mode_idx
    ON gateway_user_telegram_security_settings(security_mode);
