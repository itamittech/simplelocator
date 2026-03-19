-- ─────────────────────────────────────────────────────────────────────────────
-- V6 — Add JSONB menu column + tsvector FTS column + GIN indexes
--
-- Demonstrates two additional PostgreSQL GIN (Generalized Inverted Index) uses:
--
--  1. GIN on tsvector  — full-text search on menu item names + descriptions
--     Operator: @@  (document matches query)
--     Query fn: plainto_tsquery('english', 'pasta vegetarian')
--
--  2. GIN on JSONB     — structural/containment search on nested menu documents
--     Operator: @>  (left JSONB contains right JSONB)
--     Example:  menu @> '{"dietary_options":["vegetarian"]}'
--
-- Combined with the GiST spatial index already on location_gist, a single query
-- can answer: "Find restaurants within 5 miles that serve <dish> AND are <dietary>"
-- using THREE different PostgreSQL index types simultaneously.
-- ─────────────────────────────────────────────────────────────────────────────

-- JSONB document: stores full menu with items, dietary options, price range
ALTER TABLE restaurants ADD COLUMN menu JSONB;

-- tsvector: pre-computed weighted lexemes from name + cuisine + menu item text
-- Maintained by V7 data migration; in production would be kept fresh via trigger
ALTER TABLE restaurants ADD COLUMN menu_search_vector TSVECTOR;

-- GIN index on tsvector — O(log N) full-text match via inverted posting lists
CREATE INDEX idx_restaurants_menu_fts
    ON restaurants USING GIN(menu_search_vector);

-- GIN index on JSONB — enables fast @>, ?, ?|, ?& operators on the document
CREATE INDEX idx_restaurants_menu_jsonb
    ON restaurants USING GIN(menu);
