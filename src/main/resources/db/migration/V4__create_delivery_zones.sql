CREATE TABLE IF NOT EXISTS delivery_zones (
    id       BIGSERIAL    PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    color    VARCHAR(20)  NOT NULL DEFAULT '#3b82f6',
    boundary GEOMETRY(Polygon, 4326) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_delivery_zones_boundary
    ON delivery_zones USING GIST (boundary);
