-- Enable PostGIS extension (required for spatial types and functions)
CREATE EXTENSION IF NOT EXISTS postgis;

-- ─────────────────────────────────────────────────────────────────────────────
-- Restaurants table
--
-- Three spatial columns are stored to independently demonstrate each index:
--   location_gist   → will get a GiST  index (R-tree)
--   location_spgist → will get an SP-GiST index (Quadtree)
--   geohash         → will get a B-tree index (Geohash string prefix search)
--
-- Keeping two geometry columns is intentional: it lets PostgreSQL's query
-- planner choose the right index for each query type without ambiguity.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS restaurants (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    address         VARCHAR(255),
    cuisine         VARCHAR(100),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,

    -- Used by GiST (R-tree) queries via ST_DWithin
    location_gist   GEOMETRY(Point, 4326),

    -- Used by SP-GiST (Quadtree) queries via && bounding-box operator
    location_spgist GEOMETRY(Point, 4326),

    -- Geohash string (precision 9 ≈ 4m×5m accuracy)
    -- Used by B-tree prefix queries: geohash LIKE 'prefix%'
    geohash         VARCHAR(12)
);
