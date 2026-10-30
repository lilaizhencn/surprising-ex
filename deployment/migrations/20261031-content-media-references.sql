-- Saved drafts and immutable historical legal revisions retain their media.
-- Only uploads older than 24 hours and currently unattached are eligible for bounded cleanup.
CREATE TABLE IF NOT EXISTS gateway_content_media_references (
    owner_type VARCHAR(16) NOT NULL CHECK(owner_type IN ('ANNOUNCEMENT','LEGAL')),
    owner_id BIGINT NOT NULL,
    media_id UUID NOT NULL REFERENCES gateway_content_media(media_id),
    PRIMARY KEY(owner_type,owner_id,media_id)
);
CREATE INDEX IF NOT EXISTS gateway_content_media_reference_media_idx ON gateway_content_media_references(media_id);
INSERT INTO gateway_content_media_references(owner_type,owner_id,media_id)
SELECT DISTINCT 'ANNOUNCEMENT',t.announcement_id,(m.matches)[1]::uuid
FROM gateway_announcement_translations t
CROSS JOIN LATERAL regexp_matches(t.body_html,'/api/v1/content/media/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})','g') AS m(matches)
JOIN gateway_content_media media ON media.media_id=(m.matches)[1]::uuid
ON CONFLICT DO NOTHING;
INSERT INTO gateway_content_media_references(owner_type,owner_id,media_id)
SELECT DISTINCT 'LEGAL',t.revision_id,(m.matches)[1]::uuid
FROM gateway_legal_translations t
CROSS JOIN LATERAL regexp_matches(t.body_html,'/api/v1/content/media/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})','g') AS m(matches)
JOIN gateway_content_media media ON media.media_id=(m.matches)[1]::uuid
ON CONFLICT DO NOTHING;
