CREATE TABLE IF NOT EXISTS core_event_projection (
    product_line VARCHAR(32) NOT NULL,
    export_sequence BIGINT NOT NULL,
    applied_command_count BIGINT NOT NULL,
    business_state_hash BIGINT NOT NULL,
    command_id UUID NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    command_status VARCHAR(16) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    raw_event BYTEA NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_line, export_sequence)
);

CREATE INDEX IF NOT EXISTS idx_core_event_projection_command
    ON core_event_projection (product_line, command_id);
