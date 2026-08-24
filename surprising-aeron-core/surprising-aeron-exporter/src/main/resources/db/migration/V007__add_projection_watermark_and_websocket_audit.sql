CREATE TABLE IF NOT EXISTS core_projection_watermark (
    product_line VARCHAR(32) NOT NULL,
    last_export_sequence BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_line),
    CHECK (last_export_sequence >= 0)
);

INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'SPOT', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'SPOT'
);
INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'LINEAR_PERPETUAL', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'LINEAR_PERPETUAL'
);
INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'INVERSE_PERPETUAL', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'INVERSE_PERPETUAL'
);
INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'LINEAR_DELIVERY', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'LINEAR_DELIVERY'
);
INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'INVERSE_DELIVERY', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'INVERSE_DELIVERY'
);
INSERT INTO core_projection_watermark (product_line, last_export_sequence)
SELECT 'OPTION', 0 WHERE NOT EXISTS (
    SELECT 1 FROM core_projection_watermark WHERE product_line = 'OPTION'
);
