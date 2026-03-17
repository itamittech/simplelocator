-- ─────────────────────────────────────────────────────────────────────────────
-- Geospatial Index Definitions
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. GiST Index (R-tree) ───────────────────────────────────────────────────
-- GiST = Generalized Search Tree
-- PostGIS uses R-tree semantics inside GiST for geometry types.
--
-- How it works:
--   Geometries are grouped into Minimum Bounding Rectangles (MBRs).
--   The tree is built bottom-up: nearby MBRs are merged into larger parent MBRs.
--   A radius search traverses the tree, pruning branches whose MBR doesn't
--   intersect the search circle.
--
-- Best for: ST_DWithin, ST_Intersects, ST_Contains — any spatial predicate.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_restaurants_gist
    ON restaurants USING GIST (location_gist);


-- ── 2. SP-GiST Index (Quadtree) ──────────────────────────────────────────────
-- SP-GiST = Space-Partitioning GiST
-- PostGIS builds a Quadtree inside SP-GiST for geometry types.
--
-- How it works:
--   Space is recursively divided into 4 equal quadrants (hence "quad-tree").
--   Each node covers exactly one quadrant — no overlapping MBRs like in R-tree.
--   Queries using the && (bounding-box overlap) operator navigate the
--   quadrant hierarchy to find candidates, then apply exact filtering.
--
-- Best for: bounding-box queries (&&), uniformly distributed point data.
-- Slightly faster than GiST for large, uniformly distributed datasets.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_restaurants_spgist
    ON restaurants USING SPGIST (location_spgist);


-- ── 3. B-tree Index on Geohash (Geohash prefix search) ───────────────────────
-- Geohash encodes lat/lng into a base-32 string preserving spatial locality.
-- Nearby locations share common leading characters (prefixes).
--
-- How it works:
--   The world is recursively divided into rectangular cells.
--   Each level of precision adds one character, shrinking the cell size.
--   A proximity search: take the query point's geohash prefix at a coarse
--   precision, add its 8 neighbors, then do LIKE 'prefix%' on the B-tree index.
--   A second pass with ST_DWithin removes false positives from cell corners.
--
-- Best for: key-value stores (Redis, DynamoDB), systems without native spatial
-- indexes, or as a fast pre-filter before exact computation.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_restaurants_geohash
    ON restaurants (geohash);


-- ── Supporting index: lat/lng for display queries ────────────────────────────
CREATE INDEX IF NOT EXISTS idx_restaurants_lat_lng
    ON restaurants (latitude, longitude);
