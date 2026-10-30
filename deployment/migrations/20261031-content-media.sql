-- Repeatable. Plain announcement bodies keep their text semantics.
ALTER TABLE gateway_announcement_translations ADD COLUMN IF NOT EXISTS body_html VARCHAR(64000);
CREATE TABLE IF NOT EXISTS gateway_content_media (
    media_id UUID PRIMARY KEY,
    object_key VARCHAR(200) NOT NULL UNIQUE,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes>0 AND size_bytes<=67108864),
    uploaded_by BIGINT NOT NULL REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS gateway_content_media_created_idx ON gateway_content_media(created_at);
