-- KYC 会话恢复与回调顺序。先迁移，再启动新版 Gateway。
ALTER TABLE gateway_user_kyc_profiles ADD COLUMN IF NOT EXISTS provider_event_at TIMESTAMPTZ;
ALTER TABLE gateway_user_kyc_profiles ADD COLUMN IF NOT EXISTS provider_redirect_url TEXT;
COMMENT ON COLUMN gateway_user_kyc_profiles.provider_event_at IS '当前认证会话已接受的提供方事件时间水位；用于拒绝旧回执覆盖新结果。';
COMMENT ON COLUMN gateway_user_kyc_profiles.provider_redirect_url IS '提供方返回的 HTTPS 核验地址；用于恢复当前待审会话，不返回其他用户地址。';
