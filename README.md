# SimpleLocator — Geospatial Index Demo

A Spring Boot application that demonstrates **three geospatial indexing strategies** in
PostgreSQL/PostGIS by finding restaurants within **5 miles** of a user-supplied location.

All three methods return the same results (correctness) but use fundamentally different
index types and query patterns — making it easy to compare them with `EXPLAIN ANALYZE`.

---

## What This Demonstrates

| Index | PostgreSQL Keyword | Data Structure | Primary Operator |
|-------|-------------------|----------------|-----------------|
| **GiST** | `USING GIST` | R-tree | `ST_DWithin` |
| **SP-GiST** | `USING SPGIST` | Quadtree | `&&` (bbox overlap) |
| **Geohash** | `USING btree` | Hash string prefix | `LIKE 'prefix%'` |

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

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Spring Boot 3.2.3, Java 21+ (tested on JDK 25) |
| Database | PostgreSQL 16 + PostGIS 3.4 |
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

### 2. Run the application

```bash
./mvnw.cmd spring-boot:run        # Windows
```

On first boot, Flyway automatically runs:
1. `V1` — enables the PostGIS extension and creates the `restaurants` table
2. `V2` — creates GiST, SP-GiST, and B-tree (geohash) indexes
3. `V3` — inserts 23 sample NYC-area restaurants

Expected output:
```
Successfully applied 3 migrations to schema "public"
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

| Method | Path | Index Used |
|--------|------|-----------|
| `POST` | `/api/restaurants/search/gist` | GiST (R-tree) |
| `POST` | `/api/restaurants/search/spgist` | SP-GiST (Quadtree) |
| `POST` | `/api/restaurants/search/geohash` | Geohash B-tree |
| `POST` | `/api/restaurants/search/compare` | All three at once |

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

## Verify Indexes in pgAdmin

```sql
-- Confirm all three indexes exist
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
| `idx_restaurants_spgist` | `CREATE INDEX ... USING spgist (location_spgist)` |

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

---

## Key Constants

| Constant | Value | Notes |
|----------|-------|-------|
| Search radius | **5 miles = 8,046.72 m** | Used in all three strategies |
| SP-GiST buffer | **0.09°** | ~10 km, covers search circle at any latitude |
| Geohash search precision | **4 chars** | Cell ≈ 39×20 km, safe outer bound for 8 km radius |
| Geohash stored precision | **9 chars** | Cell ≈ 4m×5m, high accuracy for storage |
| Neighbors checked | **9 cells** | Center + 8 adjacent cells |

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
