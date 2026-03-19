# SimpleLocator — Geospatial Index Demo

A Spring Boot application that demonstrates **four geospatial indexing strategies** across
PostgreSQL/PostGIS and Redis — finding restaurants near a user-supplied location using
fundamentally different data structures.

---

## What This Demonstrates

| Index | Store | Data Structure | Primary Operator | Use-case |
|-------|-------|----------------|-----------------|----------|
| **GiST** | PostgreSQL | R-tree | `ST_DWithin` | General spatial queries |
| **SP-GiST** | PostgreSQL | Quadtree | `&&` (bbox overlap) | Uniform point data |
| **Geohash** | PostgreSQL | B-tree (string prefix) | `LIKE 'prefix%'` | Any string-indexed store |
| **GIN/tsvector** | PostgreSQL | Inverted index (lexeme → posting list) | `@@` (matches) | Full-text search on menu items |
| **GIN/JSONB** | PostgreSQL | Inverted index (path → value set) | `@>` (contains) | Structured document queries |
| **Redis GEO** | Redis | Sorted Set (geohash score) | `GEOSEARCH` | Real-time location tracking |

The **Menu Search** endpoint combines GiST + GIN (FTS) + GIN (JSONB) in a **single SQL query** — three different index types intersecting their results to answer "restaurants within 5 miles that serve *pasta* and are *vegetarian*".

---

## How Each Index Works

### 1. GiST — R-tree (Generalized Search Tree)

```
                    [Root MBR: entire dataset]
                   /                           \
        [MBR: Manhattan area]         [MBR: Brooklyn area]
        /             \                /              \
  [Joe's Pizza]  [Katz's Deli]  [Peter Luger]   [Grimaldi's]
```

- Geometries are grouped into **Minimum Bounding Rectangles (MBRs)** that form a tree.
- A radius search walks the tree: if a node's MBR doesn't intersect the search circle, its entire subtree is skipped (pruned).
- `ST_DWithin()` automatically activates the GiST index — no special operator needed.
- **Best all-around** spatial index — handles distance, overlap, contains, intersects.

### 2. SP-GiST — Quadtree (Space-Partitioning GiST)

```
          ┌────────┬────────┐
          │   NW   │   NE   │
          │  quad  │  quad  │
          ├────────┼────────┤
          │   SW   │   SE   │
          │  quad  │  quad  │
          └────────┴────────┘
    Each quadrant subdivides recursively until leaf size is reached.
```

- Space is recursively split into **4 equal quadrants** (no overlapping MBRs unlike R-tree).
- The `&&` bounding-box overlap operator navigates the quadrant hierarchy.
- Query pattern: expand the search point into a rectangle (`ST_Expand`), use `&&` to activate the SP-GiST index, then `ST_DWithin` for exact circle filtering.
- **Best for**: uniformly distributed point data, bounding-box queries.

### 3. Geohash — B-tree on encoded string

```
Precision 4: 'dr5r'        → ~39   km × 20   km  ← search precision (center + 8 neighbors)
Precision 9: 'dr5reuueb'   → ~4.4  m  × 4.4  m   ← stored precision
```

- Lat/lng is encoded as a **base-32 string** where nearby locations share common prefixes.
- Search: compute precision-4 geohash of query center + its **8 neighbors** = 9 candidate cells.
- `LIKE 'dr5r%'` on a B-tree index finds all restaurants in those cells instantly.
- `ST_DWithin` then removes false positives (cell corners outside the circle).
- **Best for**: Redis, DynamoDB, any system without native spatial indexes.

---

### 4. GIN — Full-Text Search (tsvector)

```
menu_search_vector (tsvector):

Lexeme        →  Posting List (row IDs)
──────────────────────────────────────
'pasta'       →  { 19 }           ← Battello
'pizza'       →  { 1, 11, 12, 19 }
'miso'        →  { 8 }            ← Nobu
'curry'       →  { 16, 18 }
'dumpl'       →  { 17 }           ← Nan Xiang (stemmed)
```

GIN (Generalized Inverted Index) maps each **stemmed lexeme** (word root) to a list of row IDs containing that word. A text search is a lookup in this map — O(log N + K) where K is the result count.

- The `menu_search_vector` column is a pre-computed `tsvector` covering restaurant name + cuisine + all menu item names + descriptions.
- `plainto_tsquery('english', 'pasta')` parses the user's phrase into a tsquery, applying the same stemming rules as the tsvector.
- `@@` (matches) operator: `menu_search_vector @@ plainto_tsquery('english', 'pasta')` — GIN looks up `'pasta'` in the index and returns its posting list in one page read.
- Results are ranked by `ts_rank()` — term frequency × positional weight.
- **Best for**: document search, menu/catalogue search, any unstructured text.

```sql
-- GIN index creation:
CREATE INDEX idx_restaurants_menu_fts ON restaurants USING GIN(menu_search_vector);

-- Query pattern:
WHERE menu_search_vector @@ plainto_tsquery('english', 'pasta')
ORDER BY ts_rank(menu_search_vector, plainto_tsquery('english', 'pasta')) DESC
```

---

### 5. GIN — JSONB Document Index

```json
{
  "items": [
    { "name": "Veggie Ramen", "price": 19.00, "dietary": ["vegetarian","vegan"] },
    { "name": "Pork Ramen",   "price": 21.00, "dietary": [] }
  ],
  "dietary_options": ["vegetarian", "vegan"],
  "price_range": "$$"
}
```

When you create a GIN index on a JSONB column, PostgreSQL decomposes every document into its constituent key-value paths and indexes them individually. The result is an inverted index over the document structure itself.

- `@>` (containment): `menu @> '{"dietary_options":["vegetarian"]}'` — returns rows whose menu document contains the right-hand side as a subset.
- GIN indexes every path and value: `dietary_options`, `dietary_options[0]`, etc.
- Empty object `'{}'::jsonb` is contained by everything — use it as a "no filter" sentinel.
- **Best for**: flexible schema queries, tag/label searches, nested document filtering.

```sql
-- GIN index creation:
CREATE INDEX idx_restaurants_menu_jsonb ON restaurants USING GIN(menu);

-- Query pattern (dietary filter):
WHERE menu @> '{"dietary_options":["vegetarian"]}'::jsonb

-- No filter (matches all):
WHERE menu @> '{}'::jsonb        -- always true
```

---

### 6. The Combined Query — GiST + GIN + GIN

This is where it comes together. A single SQL statement activates **three separate indexes**:

```sql
SELECT r.id, r.name, r.cuisine, r.menu,
       ts_rank(r.menu_search_vector, plainto_tsquery('english', :query)) AS rank,
       ST_Distance(CAST(r.location_gist AS geography), ST_MakePoint(:lng,:lat)) AS dist_m
FROM restaurants r
WHERE
    -- ① GiST R-tree: prune by location (only rows within 5 miles)
    ST_DWithin(
        CAST(r.location_gist AS geography),
        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
        8046.72
    )
    -- ② GIN inverted index: prune by menu text (only rows matching query)
    AND r.menu_search_vector @@ plainto_tsquery('english', :query)

    -- ③ GIN JSONB index: prune by dietary requirement
    AND r.menu @> CAST(:dietary AS jsonb)

ORDER BY rank DESC, dist_m ASC   -- most relevant first, then closest
```

PostgreSQL's **bitmap scan** mechanism intersects the row sets from each index independently:

```
GiST result set:      { 1, 2, 3, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 19, 20 }
GIN/FTS result set:   { 19, 7, 4, 11 }   (rows with "pasta" or "vegetable")
GIN/JSONB result set: { 4, 7, 10, 11, 16, 19 }   (vegetarian menu)
                       ───────────────────────
Intersection:         { 7, 11, 19 }
```

Only the intersection is fetched from the heap — rows that are nearby *and* relevant *and* match the dietary filter.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Spring Boot 3.2.3, Java 21+ (tested on JDK 25) |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| Cache / Geo | Redis 7 (via Spring Data Redis + Lettuce) |
| ORM | Spring Data JPA + Hibernate Spatial 6.4 |
| Geometry | JTS (LocationTech) 1.19.0 |
| Geohash | `ch.hsr:geohash:1.4.0` |
| Migrations | Flyway 9.x |
| DB Admin | pgAdmin 4 |
| Container | Docker Compose |

---

## Prerequisites

- Docker & Docker Compose
- Java 21+ (`java -version`)
- `mvnw.cmd` wrapper (included — no separate Maven install needed)

---

## Quick Start

### 1. Start the database

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL 16 + PostGIS 3.4** on `localhost:5432`
- **pgAdmin 4** on `http://localhost:5050`
- **Redis 7** on `localhost:6379`

### 2. Run the application

```bash
./mvnw.cmd spring-boot:run        # Windows
```

On first boot, Flyway automatically runs:
1. `V1` — enables PostGIS extension and creates the `restaurants` table
2. `V2` — creates GiST, SP-GiST, and B-tree (geohash) indexes
3. `V3` — inserts 23 sample NYC-area restaurants
4. `V4` — creates `delivery_zones` table with PostGIS Polygon column
5. `V5` — inserts 3 Manhattan/Brooklyn delivery zones
6. `V6` — adds `menu` JSONB column + `menu_search_vector` tsvector + two GIN indexes
7. `V7` — populates realistic menu JSONB for all 23 restaurants and computes tsvector

Expected output:
```
Successfully applied 7 migrations to schema "public"
Started SimpleLocatorApplication in 3.7 seconds
```

### 3. Connect to pgAdmin (optional)

1. Open `http://localhost:5050`
2. Login: `admin@simplelocator.com` / `admin123`
3. Add server:
   - **Host**: `host.docker.internal`
   - **Port**: `5432`
   - **Database**: `simplelocator`
   - **Username / Password**: `simplelocator` / `simplelocator123`

---

## API Reference

All endpoints accept a `POST` body with the user's current coordinates:

```json
{
  "latitude":  40.7128,
  "longitude": -74.0060
}
```

### PostGIS endpoints (5-mile search)

| Method | Path | Index Used |
|--------|------|-----------|
| `POST` | `/api/restaurants/search/gist` | GiST (R-tree) |
| `POST` | `/api/restaurants/search/spgist` | SP-GiST (Quadtree) |
| `POST` | `/api/restaurants/search/geohash` | Geohash B-tree |
| `POST` | `/api/restaurants/search/compare` | All three at once |
| `POST` | `/api/restaurants/search/debug` | Map visualization metadata |
| `POST` | `/api/restaurants/search/menu` | **GiST + GIN (FTS) + GIN (JSONB)** combined |

**Menu search request body:**
```json
{
  "latitude":  40.7128,
  "longitude": -74.0060,
  "query":     "pasta",
  "dietary":   "vegetarian"
}
```

`dietary` is optional. Accepted values: `vegetarian`, `vegan`, `gluten-free`, `halal`, `kosher`.
Omit or pass `null` to search all restaurants regardless of dietary options.

**Menu search response shape:**
```json
{
  "centerLat": 40.7128, "centerLng": -74.006, "radiusMeters": 8046.72,
  "query": "pasta", "dietaryFilter": "vegetarian",
  "resultCount": 2, "executionTimeMs": 12,
  "indexesUsed": "GiST (location) + GIN/tsvector (FTS) + GIN/JSONB (dietary)",
  "restaurants": [
    {
      "id": 19, "name": "Battello", "cuisine": "Italian",
      "distanceMiles": 3.21, "relevanceScore": 0.076,
      "priceRange": "$$$",
      "dietaryOptions": ["vegetarian", "gluten-free"],
      "items": [
        {
          "name": "Wild Mushroom Risotto",
          "description": "Arborio rice with wild mushrooms, white wine, and truffle oil",
          "price": 28.00,
          "dietary": ["vegetarian", "gluten-free"]
        }
      ]
    }
  ]
}
```

```bash
curl -X POST http://localhost:8080/api/restaurants/search/menu \
  -H "Content-Type: application/json" \
  -d '{"latitude":40.7128,"longitude":-74.0060,"query":"pasta","dietary":"vegetarian"}'
```

### Redis GEO endpoints (Rider mode, 2-mile search)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/rider/nearby` | GEORADIUS search from rider's current position |
| `GET`  | `/api/rider/restaurants` | All restaurants from Redis cache (map init) |

**Rider request body:**
```json
{
  "latitude":    40.7580,
  "longitude":  -73.9855,
  "radiusMiles": 2.0
}
```

**Rider response:**
```json
{
  "searchLat": 40.758, "searchLng": -73.9855,
  "radiusMiles": 2.0, "executionTimeMs": 1, "count": 5,
  "restaurants": [
    { "id": 3, "name": "Ippudo NY", "cuisine": "Japanese", "distanceMiles": 0.34, ... }
  ]
}
```

```bash
curl -X POST http://localhost:8080/api/rider/nearby \
  -H "Content-Type: application/json" \
  -d '{"latitude": 40.7580, "longitude": -73.9855, "radiusMiles": 2.0}'
```

### Example — GiST search

```bash
curl -X POST http://localhost:8080/api/restaurants/search/gist \
  -H "Content-Type: application/json" \
  -d '{"latitude": 40.7128, "longitude": -74.0060}'
```

### Response structure

```json
{
  "indexType": "GiST (R-tree)",
  "description": "GiST implements an R-tree in PostGIS...",
  "count": 17,
  "executionTimeMs": 8,
  "restaurants": [
    {
      "id": 8,
      "name": "Nobu",
      "address": "105 Hudson St, New York, NY",
      "cuisine": "Japanese",
      "latitude": 40.7201,
      "longitude": -74.0087,
      "distanceMiles": 0.52,
      "geohash": "dr5reuueb"
    }
  ]
}
```

### Compare all three indexes in one call

```bash
curl -X POST http://localhost:8080/api/restaurants/search/compare \
  -H "Content-Type: application/json" \
  -d '{"latitude": 40.7128, "longitude": -74.0060}'
```

Returns a JSON object keyed by `"gist"`, `"spgist"`, and `"geohash"` — each with
their own result set, count, and `executionTimeMs` for direct comparison.

---

## Code Walkthrough

### Project Structure

```
simplelocator/
├── docker-compose.yml
├── pom.xml
└── src/main/
    ├── java/com/example/simplelocator/
    │   ├── SimpleLocatorApplication.java       # @SpringBootApplication entry point
    │   ├── controller/
    │   │   └── RestaurantController.java        # 4 REST endpoints
    │   ├── dto/
    │   │   ├── LocationRequest.java             # { latitude, longitude }
    │   │   ├── RestaurantResponse.java          # Restaurant + distanceMiles
    │   │   └── SearchResult.java               # Results + indexType + timing
    │   ├── entity/
    │   │   └── Restaurant.java                  # JPA entity — 3 spatial columns
    │   ├── repository/
    │   │   └── RestaurantRepository.java        # 3 native SQL queries
    │   └── service/
    │       ├── GeohashService.java              # Prefix computation (center + 8 neighbors)
    │       └── RestaurantService.java           # Orchestration + timing
    └── resources/
        ├── application.yml
        └── db/migration/
            ├── V1__create_restaurants_table.sql  # PostGIS + DDL
            ├── V2__create_indexes.sql            # GiST + SP-GiST + B-tree indexes
            └── V3__insert_sample_data.sql        # 23 NYC restaurants
```

---

### Entity — `Restaurant.java`

The key design decision: **three spatial columns** on one table, each indexed differently.

```java
@Entity
@Table(name = "restaurants")
public class Restaurant {
    // ...

    // Column for GiST (R-tree) index queries
    @Column(name = "location_gist", columnDefinition = "geometry(Point, 4326)")
    private Point locationGist;

    // Column for SP-GiST (Quadtree) index queries
    @Column(name = "location_spgist", columnDefinition = "geometry(Point, 4326)")
    private Point locationSpgist;

    // Column for Geohash B-tree index queries — stores base-32 encoded string
    @Column(name = "geohash", length = 12)
    private String geohash;
}
```

`Point` is `org.locationtech.jts.geom.Point` from the JTS library, which Hibernate Spatial
maps directly to PostGIS `geometry(Point, 4326)`.

---

### Database Indexes — `V2__create_indexes.sql`

```sql
-- R-tree: groups geometries into overlapping Minimum Bounding Rectangles
CREATE INDEX idx_restaurants_gist   ON restaurants USING GIST   (location_gist);

-- Quadtree: partitions space into non-overlapping quadrants recursively
CREATE INDEX idx_restaurants_spgist ON restaurants USING SPGIST (location_spgist);

-- B-tree: standard sorted index; geohash string prefix preserves spatial locality
CREATE INDEX idx_restaurants_geohash ON restaurants (geohash);
```

---

### Repository — `RestaurantRepository.java`

Three native SQL queries, one per index strategy.

**Important caveat**: Spring Data JPA parses `:name` as a named parameter inside native
queries. The PostgreSQL cast operator `::` would be mis-parsed as a partial parameter
name (e.g., `::geography` → parameter `"geography"`). All casts use ANSI SQL
`CAST(expr AS type)` instead.

#### GiST query
```sql
SELECT r.*, ST_Distance(CAST(r.location_gist AS geography),
                         CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)) AS dist_meters
FROM restaurants r
WHERE ST_DWithin(
    CAST(r.location_gist AS geography),
    CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
    :radiusMeters          -- 8046.72 metres = 5 miles
)
ORDER BY dist_meters
```
- `ST_DWithin` on a `geometry` column cast to `geography` automatically uses the GiST index.
- `geography` type means distances are in **metres on a spherical earth** — no projection needed.

#### SP-GiST query
```sql
WHERE r.location_spgist && ST_Expand(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :bufferDegrees)
  AND ST_DWithin(CAST(r.location_spgist AS geography), ..., :radiusMeters)
```
- `&&` (bounding-box overlap) is the operator that activates the SP-GiST index.
- `ST_Expand(point, 0.09)` builds a ~10 km rectangular envelope around the search center.
- The SP-GiST index prunes quadrant branches that don't overlap that envelope.
- `ST_DWithin` then does the exact circular filter on the small candidate set.

#### Geohash query
```sql
WHERE (geohash LIKE CONCAT(:p0, '%') OR geohash LIKE CONCAT(:p1, '%') OR ... 9 cells ...)
  AND ST_DWithin(CAST(r.location_gist AS geography), ..., :radiusMeters)
```
- 9 LIKE prefix predicates — one for the center cell, one for each of 8 neighbors.
- The B-tree on `geohash` handles the prefix scan.
- `ST_DWithin` removes false positives at rectangular cell edges.

---

### GeohashService — `GeohashService.java`

Computes the 9 geohash prefix cells covering the search area using the `ch.hsr:geohash` library.

```java
private static final int SEARCH_PRECISION = 4;   // ~39×20 km per cell

public List<String> getPrefixesForSearch(double lat, double lng) {
    GeoHash center = GeoHash.withCharacterPrecision(lat, lng, SEARCH_PRECISION);

    List<String> prefixes = new ArrayList<>();
    prefixes.add(center.toBase32() + "%");         // center cell

    for (GeoHash neighbor : center.getAdjacent()) { // 8 neighbors
        prefixes.add(neighbor.toBase32() + "%");
    }
    return prefixes;  // 9 total
}
```

Why 9 cells? A single geohash cell won't fully contain a circle — the circle can straddle
up to 4 neighboring cells at corners. Using all 8 neighbors guarantees full coverage
regardless of where the query point falls within its cell.

---

### RestaurantService — `RestaurantService.java`

Wraps each repository call in `System.currentTimeMillis()` to capture execution time
and packages results into `SearchResult`:

```java
public SearchResult searchWithGist(LocationRequest req) {
    long start = System.currentTimeMillis();
    List<Object[]> rows = restaurantRepository.findWithinRadiusUsingGist(
            req.getLatitude(), req.getLongitude(), SEARCH_RADIUS_METERS  // 8046.72
    );
    long elapsed = System.currentTimeMillis() - start;

    return SearchResult.builder()
            .indexType("GiST (R-tree)")
            .description("...")
            .count(rows.size())
            .executionTimeMs(elapsed)
            .restaurants(rows.stream().map(this::mapRow).toList())
            .build();
}
```

The native query returns `Object[]` rows because of the extra `dist_meters` computed column.
`mapRow()` casts each column by position:
- `row[0]` = id, `row[1]` = name, ... `row[9]` = dist_meters

---

---

## Feature 4 — Rider Mode (Redis GEO)

### How Redis GEO works

Redis encodes `(longitude, latitude)` pairs into a **52-bit integer geohash** stored as
the score of a Sorted Set. Because sorted sets are ordered by score, a radius search
becomes a score-range scan — `O(N+log M)` where N is results and M is total members.

```
KEY: restaurants:geo  (Sorted Set)
┌──────────────────────┬───────────┐
│  score (geohash52)   │  member   │
├──────────────────────┼───────────┤
│  3.29720…×10¹⁴       │  "1"      │  → Joe's Pizza
│  3.29721…×10¹⁴       │  "5"      │  → Nobu
│  3.29724…×10¹⁴       │  "12"     │  → Le Bernardin
│  …                   │  …        │
└──────────────────────┴───────────┘
```

### Data loading (`RedisGeoService.java`)

On `ApplicationReadyEvent`, `RedisGeoService` loads all 23 restaurants into Redis and
an in-memory `Map<Long, Restaurant>` cache:

```java
@EventListener(ApplicationReadyEvent.class)
public void loadRestaurantsIntoRedis() {
    GeoOperations<String, String> geo = stringRedisTemplate.opsForGeo();
    stringRedisTemplate.delete(GEO_KEY);
    List<Restaurant> all = restaurantRepository.findAll();
    for (Restaurant r : all) {
        geo.add(GEO_KEY, new Point(r.getLongitude(), r.getLatitude()), String.valueOf(r.getId()));
        restaurantCache.put(r.getId(), r);
    }
}
```

### Query (`RedisGeoService.findNearby`)

```java
GeoResults<GeoLocation<String>> results = geo.radius(
    GEO_KEY,
    new Circle(new Point(lng, lat), new Distance(radiusMiles, Metrics.MILES)),
    RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
        .includeDistance().sortAscending().limit(20)
);
```

Maps to the Redis command:
```
GEORADIUS restaurants:geo <lng> <lat> 2 mi ASC WITHDIST COUNT 20
```

Results include the distance in miles, sorted nearest-first. Restaurant metadata
(name, cuisine, lat/lng) comes from the in-memory cache — no second Redis or DB hit.

### Rider simulation (UI)

The browser simulates a rider moving through 14 Manhattan waypoints (Financial District →
59th St), interpolating 12 steps between each pair. On every step:
1. The rider marker and 2-mile circle move on the Leaflet map
2. `POST /api/rider/nearby` fires with the new coordinates
3. Nearby restaurant dots turn green; others turn gray
4. The live Redis command is displayed in the controls bar

---

## Verify Indexes in pgAdmin

```sql
-- Confirm all five indexes exist
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'restaurants'
ORDER BY indexname;
```

Expected:

| indexname | indexdef |
|-----------|----------|
| `idx_restaurants_geohash` | `CREATE INDEX ... USING btree (geohash)` |
| `idx_restaurants_gist` | `CREATE INDEX ... USING gist (location_gist)` |
| `idx_restaurants_menu_fts` | `CREATE INDEX ... USING gin (menu_search_vector)` |
| `idx_restaurants_menu_jsonb` | `CREATE INDEX ... USING gin (menu)` |
| `idx_restaurants_spgist` | `CREATE INDEX ... USING spgist (location_spgist)` |

```sql
-- Inspect GIN index internals — tsvector entries for Battello
SELECT to_tsvector('english', name || ' ' || cuisine) AS vec
FROM restaurants WHERE name = 'Battello';
-- 'battello':1 'italian':2

-- Inspect menu JSONB document
SELECT menu FROM restaurants WHERE name = 'Battello';

-- Test dietary containment manually
SELECT name FROM restaurants
WHERE menu @> '{"dietary_options":["vegetarian"]}'::jsonb;

-- Full combined query test
SELECT name,
       ts_rank(menu_search_vector, plainto_tsquery('english','pasta')) AS rank
FROM restaurants
WHERE menu_search_vector @@ plainto_tsquery('english','pasta')
  AND menu @> '{"dietary_options":["vegetarian"]}'::jsonb
ORDER BY rank DESC;
```

---

## Inspect Query Plans with EXPLAIN ANALYZE

### GiST — should show "Index Scan using idx_restaurants_gist"

```sql
EXPLAIN ANALYZE
SELECT name,
       ST_Distance(CAST(location_gist AS geography),
                   CAST(ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326) AS geography)) AS dist_m
FROM restaurants
WHERE ST_DWithin(
    CAST(location_gist AS geography),
    CAST(ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326) AS geography),
    8046.72
);
```

### SP-GiST — should show "Index Scan using idx_restaurants_spgist"

```sql
EXPLAIN ANALYZE
SELECT name
FROM restaurants
WHERE location_spgist && ST_Expand(ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326), 0.09)
  AND ST_DWithin(
      CAST(location_spgist AS geography),
      CAST(ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326) AS geography),
      8046.72
  );
```

### Geohash — should show "Index Scan using idx_restaurants_geohash"

```sql
EXPLAIN ANALYZE
SELECT name
FROM restaurants
WHERE geohash LIKE 'dr5r%'
   OR geohash LIKE 'dr5p%'
   OR geohash LIKE 'dr5x%';
```

### Combined GiST + GIN + GIN — should show three index types

```sql
EXPLAIN ANALYZE
SELECT name,
       ts_rank(menu_search_vector, plainto_tsquery('english', 'pizza')) AS rank
FROM restaurants
WHERE ST_DWithin(
        CAST(location_gist AS geography),
        CAST(ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326) AS geography),
        8046.72
      )
  AND menu_search_vector @@ plainto_tsquery('english', 'pizza')
  AND menu @> '{"dietary_options":["vegetarian"]}'::jsonb
ORDER BY rank DESC;
```

Expected plan excerpt:
```
Bitmap Heap Scan on restaurants
  Recheck Cond: (menu_search_vector @@ ...) AND (menu @> ...)
  Filter: ST_DWithin(CAST(location_gist AS geography), ...)
  ->  BitmapAnd
        ->  Bitmap Index Scan on idx_restaurants_menu_fts
        ->  Bitmap Index Scan on idx_restaurants_menu_jsonb
```

PostgreSQL generates a `BitmapAnd` — bitwise-AND of the posting lists from both GIN indexes — then applies the GiST spatial filter on the small survivor set.

---

## Key Constants

| Constant | Value | Notes |
|----------|-------|-------|
| Search radius | **5 miles = 8,046.72 m** | Used in all strategies |
| SP-GiST buffer | **0.09°** | ~10 km, covers search circle at any latitude |
| Geohash search precision | **4 chars** | Cell ≈ 39×20 km, safe outer bound for 8 km radius |
| Geohash stored precision | **9 chars** | Cell ≈ 4m×5m, high accuracy for storage |
| Neighbors checked | **9 cells** | Center + 8 adjacent cells |
| GIN tsvector language | **english** | Applies Porter stemming: "pasta" → 'pasta', "pastas" → 'pasta' |
| JSONB no-filter sentinel | **`{}`** | Empty object `@>` any JSONB → always true |
| Dietary filter format | `{"dietary_options":["X"]}` | Containment check against menu's dietary_options array |

---

## Compatibility Notes

- **JDK 25**: Lombok 1.18.30 (default for Spring Boot 3.2.3) breaks on JDK 25 due to removed
  internal compiler API (`TypeTag.UNKNOWN`). This project pins `lombok.version=1.18.44` and
  `maven-compiler-plugin.version=3.13.0` in `pom.xml` to fix this.
- **PostgreSQL cast syntax in JPA**: `::geography` causes Spring Data JPA to misparse `::`
  as a named parameter prefix. All casts use ANSI SQL `CAST(expr AS geography)` instead.

---

## Stopping / Cleanup

```bash
# Stop containers (data preserved)
docker-compose down

# Stop and remove volumes (wipes DB)
docker-compose down -v
```
