# Technical Architecture — SimpleLocator

> A geospatial systems reference for engineers preparing for system design interviews.
> This document covers the full stack: PostGIS spatial indexes, Redis GEO, SSE event streaming,
> and the design decisions that make each approach appropriate for different access patterns.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Data Model Design](#3-data-model-design)
4. [PostGIS Spatial Index Deep Dive](#4-postgis-spatial-index-deep-dive)
   - 4.1 [GiST — R-tree Index](#41-gist--r-tree-index)
   - 4.2 [SP-GiST — Quadtree Index](#42-sp-gist--quadtree-index)
   - 4.3 [Geohash — B-tree Index](#43-geohash--b-tree-index)
   - 4.4 [Index Comparison](#44-index-comparison)
5. [Redis GEO Architecture](#5-redis-geo-architecture)
6. [Geofencing with PostGIS](#6-geofencing-with-postgis)
7. [SSE Real-Time Event Stream](#7-sse-real-time-event-stream)
8. [Complete Rider Event Flow](#8-complete-rider-event-flow)
9. [PostGIS Function Reference](#9-postgis-function-reference)
10. [System Design Tradeoffs](#10-system-design-tradeoffs)

---

## 1. System Overview

SimpleLocator is a learning platform that demonstrates three fundamentally different geospatial indexing strategies answering the same query — *"find all restaurants within 5 miles of this point"* — alongside a Redis-based real-time proximity engine with PostGIS geofencing and Server-Sent Events.

### Technology Stack

| Layer            | Technology                | Version | Role                                       |
|------------------|---------------------------|---------|---------------------------------------------|
| Application      | Spring Boot               | 3.2.3   | REST API, SSE, Thymeleaf UI                 |
| ORM              | Hibernate Spatial         | 6.4     | JTS geometry type mapping                   |
| Primary DB       | PostgreSQL                | 16      | ACID storage, spatial queries               |
| Spatial Ext.     | PostGIS                   | 3.4     | GiST/SP-GiST indexes, ST_* functions        |
| Cache / Geo      | Redis                     | 7       | GEO sorted set, proximity scan              |
| Geometry Library | JTS (LocationTech)        | 1.19    | In-JVM polygon/point representation         |
| Frontend         | Leaflet.js                | 1.9.4   | OSM tile maps                               |
| Migration        | Flyway                    | 9.x     | Schema versioning                           |
| Runtime          | JDK                       | 25      | Java runtime                                |

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Browser (Leaflet.js)                        │
│                                                                       │
│  ┌───────────────┐  ┌───────────────┐  ┌──────────────────────────┐ │
│  │  Search Panel │  │  Map Viz Tabs │  │  Rider Mode              │ │
│  │  (3 indexes)  │  │  GiST/SP/Hash │  │  Simulation + Event Log  │ │
│  └───────┬───────┘  └──────┬────────┘  └───────────┬──────────────┘ │
│          │                 │                        │                 │
│    POST /compare      POST /debug            POST /update            │
│                                         GET /stream (SSE EventSource)│
└──────────┼─────────────────┼────────────────────────┼────────────────┘
           │                 │                        │
           ▼                 ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Spring Boot Application                         │
│                                                                       │
│  RestaurantController        RiderController                          │
│  ┌──────────────────┐        ┌────────────────────────────────────┐  │
│  │ POST /compare    │        │ POST /update  → RiderService       │  │
│  │ POST /debug      │        │ GET  /stream  → EventStreamService │  │
│  └────────┬─────────┘        │ GET  /zones   → GeofenceService    │  │
│           │                  └────────────┬───────────────────────┘  │
│           │                               │                           │
│  ┌────────▼─────────┐        ┌────────────▼────────────────────────┐ │
│  │ RestaurantService│        │           RiderService               │ │
│  │                  │        │  ┌─────────────┐ ┌───────────────┐  │ │
│  │ Haversine dist.  │        │  │RedisGeoSvc  │ │GeofenceService│  │ │
│  │ GiST query       │        │  │(GEOSEARCH)  │ │(ST_Within)    │  │ │
│  │ SP-GiST query    │        │  └──────┬──────┘ └───────┬───────┘  │ │
│  │ Geohash query    │        │         │                 │          │ │
│  └────────┬─────────┘        │  ┌──────▼─────────────────▼───────┐ │ │
│           │                  │  │       EventStreamService        │ │ │
│           │                  │  │  CopyOnWriteArrayList<SseEmitter│ │ │
│           │                  │  └─────────────────────────────────┘ │ │
│           │                  └─────────────────────────────────────┘  │
└───────────┼────────────────────────────┬───────────────────────────┘
            │                            │
    ┌───────▼──────┐             ┌────────▼──────┐
    │  PostgreSQL  │             │    Redis 7    │
    │   PostGIS    │             │               │
    │              │             │  Sorted Set   │
    │  restaurants │             │  restaurants  │
    │  (GiST col)  │             │  :geo         │
    │  (SP-GiST col│             │               │
    │  geohash col)│             │  score =      │
    │              │             │  52-bit hash  │
    │ delivery_zones             │               │
    │  (Polygon)   │             └───────────────┘
    └──────────────┘
```

### Request Lifecycle

```
Browser                Spring Boot             PostgreSQL / Redis
  │                        │                          │
  │── POST /compare ───────►│                          │
  │                        │── GiST query ────────────►│
  │                        │◄─ [{id,name,dist}, ...] ──│
  │                        │── SP-GiST query ──────────►│
  │                        │◄─ [{id,name,dist}, ...] ──│
  │                        │── Geohash query ───────────►│
  │                        │◄─ [{id,name,dist}, ...] ──│
  │◄── {gist,spgist,hash} ─│                          │
```

---

## 3. Data Model Design

### 3.1 Restaurant Entity — Two Geometry Columns

The central design decision is storing **two separate geometry columns** for the same coordinates:

```sql
CREATE TABLE restaurants (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(255),
    cuisine        VARCHAR(100),
    latitude       DOUBLE PRECISION,        -- raw float, Haversine Java-side
    longitude      DOUBLE PRECISION,
    geohash        VARCHAR(20),             -- precomputed at insert time

    -- Column A: GiST R-tree index (geography type for accurate metres)
    location_gist  GEOMETRY(Point, 4326),

    -- Column B: SP-GiST Quadtree index (same coordinates, different index)
    location_spgist GEOMETRY(Point, 4326)
);

CREATE INDEX idx_restaurants_gist    ON restaurants USING GIST   (location_gist);
CREATE INDEX idx_restaurants_spgist  ON restaurants USING SPGIST (location_spgist);
CREATE INDEX idx_restaurants_geohash ON restaurants (geohash);   -- standard B-tree
```

**Why duplicate columns?**
PostgreSQL can only build one index type per column. To demonstrate all three index strategies on the same data, we need two geometry columns (GiST and SP-GiST) plus the geohash string column. In production you would pick one index — the duplication here is intentional for educational comparison.

**Why GEOMETRY(Point, 4326) and not GEOGRAPHY?**
- `GEOMETRY` uses a flat Cartesian plane. Distance calculations in degrees.
- `GEOGRAPHY` uses an ellipsoidal model (WGS 84). Distance in metres, accurate globally.
- We store as `GEOMETRY` but `CAST(... AS geography)` at query time for accurate `ST_DWithin` distance thresholds in metres.

### 3.2 DeliveryZone Entity

```sql
CREATE TABLE delivery_zones (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    color    VARCHAR(20)  NOT NULL DEFAULT '#3b82f6',
    boundary GEOMETRY(Polygon, 4326) NOT NULL  -- PostGIS polygon
);

CREATE INDEX idx_delivery_zones_boundary ON delivery_zones USING GIST (boundary);
```

The boundary is stored as a `Polygon` geometry. Three zones cover Manhattan and Brooklyn:

```
     -74.025           -73.965
        │                  │
40.785 ─┼──────────────────┼─  ← top of Midtown zone
        │  Midtown         │
        │  #16a34a         │
40.740 ─┼──────────────────┼─  ← Lower/Midtown boundary
        │  Lower Manhattan │
        │  #2563eb         │
40.700 ─┼──────────────────┼─  ← bottom of Lower Manhattan zone
```

### 3.3 In-Memory Caches

`RedisGeoService` maintains a `Map<Long, Restaurant>` populated at application startup alongside the Redis geo set. This eliminates a second DB round-trip to enrich geo results with name/cuisine:

```
ApplicationReadyEvent
        │
        ▼
SELECT * FROM restaurants      ← single DB read at startup
        │
        ├──► GEOADD restaurants:geo lng lat "id"  (for each)  → Redis
        │
        └──► restaurantCache.put(id, restaurant)              → JVM Heap
```

---

## 4. PostGIS Spatial Index Deep Dive

### 4.1 GiST — R-tree Index

**What is a GiST index?**
Generalized Search Tree. PostgreSQL's GiST framework is an abstract index structure that can be specialized for different data types. For geometry, it specializes into an **R-tree (Rectangle Tree)**.

**R-tree Data Structure**

An R-tree groups nearby objects into **Minimum Bounding Rectangles (MBRs)** hierarchically. Each internal node stores the MBR that tightly wraps all its children. Leaf nodes store the actual point MBRs (degenerate rectangles).

```
                     ┌─────────────────────────────┐
                     │        Root MBR              │
                     │  (covers all of NYC area)    │
                     └──────────┬──────────────────┘
                                │
               ┌────────────────┴──────────────────┐
               │                                   │
    ┌──────────▼──────────┐           ┌────────────▼──────────┐
    │    MBR: Manhattan   │           │    MBR: Brooklyn/      │
    │    NW (Blue)        │           │    Queens (Red)        │
    │                     │           │                        │
    │  ┌─────┐  ┌─────┐   │           │  ┌─────┐  ┌──────┐    │
    │  │Joe's│  │Nobu │   │           │  │Luger│  │Grimal│    │
    │  │Pizza│  │     │   │           │  │     │  │di's  │    │
    │  └─────┘  └─────┘   │           │  └─────┘  └──────┘    │
    └─────────────────────┘           └────────────────────────┘
```

**Query Execution — ST_DWithin with GiST**

```sql
SELECT id, name,
       ST_Distance(
           CAST(location_gist AS geography),
           CAST(ST_MakePoint(:lng, :lat) AS geography)
       ) / 1609.344 AS distance_miles
FROM restaurants
WHERE ST_DWithin(
    CAST(location_gist AS geography),
    CAST(ST_MakePoint(:lng, :lat) AS geography),
    8046.72   -- 5 miles in metres
)
ORDER BY distance_miles;
```

**Tree Traversal Algorithm:**

```
1. Start at Root MBR
2. For each child MBR:
   a. Compute: does this MBR intersect the search circle?
      (circle = center point + 5-mile radius)
   b. YES → descend into this subtree
   c. NO  → PRUNE entire subtree (never read its leaves)
3. At leaf node: exact ST_DWithin check on actual point geometry
4. Return matching rows
```

**Why this is fast:**
If the search circle falls entirely within Manhattan, the Brooklyn MBR is pruned immediately. This eliminates whole branches without ever accessing disk pages for those rows.

**CAST(... AS geography) — critical detail:**
`ST_DWithin` on `GEOMETRY` type measures in **degrees** (not metres). To use a threshold of `8046.72 metres`, we must cast to `GEOGRAPHY`. This triggers the accurate ellipsoidal distance model. However, the GiST index is on `GEOMETRY` — PostgreSQL still uses the index for the bounding-box filter, then applies the geography check on candidates.

**Note on Spring Data JPA — `CAST()` vs `::`:**
PostgreSQL's native cast `::geography` conflicts with Spring Data JPA's named parameter parser (`:`). Always use `CAST(expr AS geography)` in `@Query` native queries.

---

### 4.2 SP-GiST — Quadtree Index

**What is SP-GiST?**
Space-Partitioning GiST. Unlike the R-tree which allows overlapping MBRs, SP-GiST partitions space into **non-overlapping regions**. For points, it typically implements a **Quadtree**.

**Quadtree Data Structure**

Space is recursively divided into 4 equal quadrants. Each internal node represents a region; leaf nodes hold actual points. No MBR overlaps — each point belongs to exactly one quadrant path.

```
┌─────────────┬─────────────┐
│             │             │
│  NW         │  NE         │
│  (1 point)  │  (3 points) │
│             │   ┌────┬────┤
│             │   │NE.NW│NE.NE
│             │   ├────┼────┤
│             │   │NE.SW│NE.SE◄── 2 points here → subdivide further
├─────────────┼─────────────┤
│             │             │
│  SW         │  SE         │
│  (0 points) │  (2 points) │
│             │             │
└─────────────┴─────────────┘
```

**Query Execution — Two-Phase Filter**

```sql
SELECT id, name,
       ST_Distance(
           CAST(location_spgist AS geography),
           CAST(ST_MakePoint(:lng, :lat) AS geography)
       ) / 1609.344 AS distance_miles
FROM restaurants
WHERE location_spgist
    && ST_Expand(ST_MakePoint(:lng, :lat), :bufferDegrees)  -- Phase 1: bbox
AND ST_DWithin(
    CAST(location_spgist AS geography),
    CAST(ST_MakePoint(:lng, :lat) AS geography),
    8046.72                                                  -- Phase 2: exact
)
ORDER BY distance_miles;
```

**Phase 1 — Bounding Box Filter (`&&` operator):**
`ST_Expand` grows the center point into a square bounding box (`bufferDegrees ≈ 0.09°` ≈ 5 miles). The `&&` operator activates the SP-GiST index — only quadrant nodes whose region **overlaps the bbox** are read. Non-overlapping quadrants are pruned entirely.

**Phase 2 — Exact Distance Filter (`ST_DWithin`):**
Candidates from Phase 1 include points inside the bbox but outside the actual circle (the "corners" of the square). `ST_DWithin` eliminates those false positives.

```
         ┌───────────────────┐  ← ST_Expand bbox (square)
         │ ●   ●             │
         │         ●         │
         │     ◎─────────    │  ← ST_DWithin circle
         │    (● ●       )   │
         │   (     ●      )  │
         │    (          )   │
         │ ●   ───────────   │
         │                   │
         └───────────────────┘
         ● = in bbox, outside circle → Phase 2 rejects
         ● = in circle → result
```

**SP-GiST vs GiST:**
- SP-GiST has **no MBR overlap** → less wasted I/O for uniformly distributed data
- SP-GiST trees are **shallower** for uniform distributions → fewer index page reads
- GiST handles **clustered / irregular** distributions better (MBRs shrink to fit)
- For NYC restaurant data (moderately clustered), GiST tends to win slightly

---

### 4.3 Geohash — B-tree Index

**What is a Geohash?**
Geohash encodes a (latitude, longitude) pair into a base-32 string by recursively bisecting the world's bounding box. Each additional character adds precision by halving the cell size.

**Encoding Algorithm:**

```
World bbox: lat[-90,90], lng[-180,180]

Character 1: divide into 8×4 grid → 32 cells, pick one → e.g., 'd'
Character 2: subdivide that cell → 'r'
Character 3: subdivide → '5'
Character 4: subdivide → 'q'  (precision 4: ~40km × 20km cell)

Result: "dr5q" covers roughly central Manhattan

Precision 9 storage: "dr5qvnkh9" ≈ 4.8m × 4.8m cell (per restaurant)
```

**Proximity Property — Shared Prefixes:**

```
dr5p │ dr5r │ dr5x
─────┼──────┼─────
dr5n │ dr5q ★ │ dr5w      ★ = search center cell
─────┼──────┼─────
dr5j │ dr5m │ dr5t

Places sharing the prefix "dr5q" are within the same ~40km cell.
Neighbor cells (8 surrounding) are found via Geohash neighbor algorithm.
```

**Query Execution — Prefix Scan:**

```sql
SELECT id, name,
       (6371000 * acos(cos(radians(:lat)) * cos(radians(latitude))
            * cos(radians(longitude) - radians(:lng))
            + sin(radians(:lat)) * sin(radians(latitude)))) / 1609.344
       AS distance_miles
FROM restaurants
WHERE (geohash LIKE 'dr5q%'    -- center cell
   OR  geohash LIKE 'dr5r%'    -- east neighbor
   OR  geohash LIKE 'dr5n%'    -- west neighbor
   OR  geohash LIKE 'dr5p%'    -- south-east neighbor
   OR  geohash LIKE 'dr5x%'    -- north-east neighbor
   OR  geohash LIKE 'dr5w%'    -- north neighbor
   OR  geohash LIKE 'dr5m%'    -- south neighbor
   OR  geohash LIKE 'dr5t%'    -- south-west neighbor
   OR  geohash LIKE 'dr5j%')   -- ... (9 total)
AND (haversine formula) < 8046.72
ORDER BY distance_miles;
```

**B-tree Prefix Scan:**
Because geohash is a `VARCHAR` column with a standard B-tree index, `LIKE 'dr5q%'` becomes a **range scan**: `WHERE geohash >= 'dr5q' AND geohash < 'dr5s'`. The B-tree can satisfy this entirely from the index without a heap scan for each matched row.

**Why 9 cells (not 1)?**
The center cell alone would miss restaurants near the cell boundary. The 8 neighbors guarantee complete coverage of the ~5-mile radius for precision-4 cells (~40km width). Some false positives exist at corners — removed by the Haversine check.

**Geohash limitation — boundary problem:**

```
           │
   dr5n    │    dr5q    ← center cell
           │
    R ●────┼────● R     R = restaurant just inside neighbor cell
           │            Both are equidistant from center, but only
           │            dr5q is the "center" — need BOTH cells
```

This is why we always query center + 8 neighbors. Querying only 1 cell would miss equidistant restaurants across cell boundaries.

---

### 4.4 Index Comparison

| Dimension              | GiST (R-tree)         | SP-GiST (Quadtree)     | Geohash (B-tree)           |
|------------------------|-----------------------|------------------------|----------------------------|
| **Index structure**    | Hierarchical MBRs     | Space partitioning     | String prefix tree          |
| **Overlap**            | MBRs can overlap      | No overlap             | No overlap                  |
| **Optimal data**       | Clustered / irregular | Uniform distribution   | Any — works in any DB       |
| **Query type**         | ST_DWithin (native)   | && + ST_DWithin        | LIKE prefix + Haversine     |
| **Distance unit**      | Metres (geography)    | Metres (geography)     | Java-side Haversine         |
| **False positives**    | Minimal               | Square-circle corners  | Cell boundary corners       |
| **Portability**        | PostGIS only          | PostGIS only           | MySQL, DynamoDB, Redis, etc.|
| **Update cost**        | Higher (MBR rebalance)| Lower                  | Lowest (string update)      |
| **Works without PostGIS**| No                 | No                     | Yes                         |

**When to use each in production:**
- **GiST** — default choice for all PostGIS workloads; handles polygons, lines, irregular point clouds
- **SP-GiST** — prefer when points are uniformly distributed and writes are infrequent
- **Geohash** — when you need portability across databases, or when the DB has no spatial extension

---

## 5. Redis GEO Architecture

### 5.1 Sorted Set as a Geo Index

Redis does not have a dedicated spatial data type. Its GEO commands are implemented **on top of a sorted set (ZSET)**, where the score is a **52-bit geohash integer** derived from the coordinates:

```
Key: "restaurants:geo"   Type: Sorted Set (ZSET)

Member        │ Score (52-bit integer geohash)
──────────────┼────────────────────────────────────
"1"           │ 3.29720 × 10¹⁴   ← Joe's Pizza
"5"           │ 3.29721 × 10¹⁴   ← Nobu
"8"           │ 3.29721 × 10¹⁴   ← Le Bernardin
"12"          │ 3.29724 × 10¹⁴   ← Grimaldi's
...           │ ...
```

Because nearby locations produce close geohash values, a **range scan on the sorted set scores** approximates a radius search. The scan returns a small set of candidates; Redis then filters by exact distance.

**Why IDs as members (not names)?**
Redis stores members as strings. We store restaurant IDs. Metadata (name, cuisine) is never stored in Redis — it lives in the in-JVM `restaurantCache` (`Map<Long, Restaurant>`). This avoids a DB round-trip per result and keeps Redis memory minimal.

### 5.2 GEOADD — Loading Data

```
GEOADD restaurants:geo
  -74.0087 40.7201 "8"          ← lng THEN lat (GIS convention)
  -74.0060 40.7128 "1"
  ...
```

**Important:** Redis GEO follows the GIS convention of `longitude, latitude` (not lat/lng). Spring Data Redis uses `new Point(longitude, latitude)`.

### 5.3 GEOSEARCH — Proximity Query

Modern Redis 6.2+ command replacing deprecated `GEORADIUS`:

```
GEOSEARCH restaurants:geo
  FROMLONLAT -74.0003 40.7200
  BYRADIUS 2 mi
  ASC
  WITHCOORD
  WITHDIST
  COUNT 20
```

**Spring Data Redis implementation:**

```java
GeoResults<GeoLocation<String>> results = geo.search(
    GEO_KEY,
    GeoReference.fromCoordinate(new Point(lng, lat)),   // FROMLONLAT
    new RadiusShape(new Distance(radiusMiles, Metrics.MILES)), // BYRADIUS
    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
        .includeDistance()   // WITHDIST
        .sortAscending()     // ASC
        .limit(20)           // COUNT 20
);
```

**Key class locations (Spring Data Redis 3.2.3):**

```
org.springframework.data.redis.domain.geo.RadiusShape
org.springframework.data.redis.domain.geo.GeoReference
org.springframework.data.redis.connection.RedisGeoCommands$GeoSearchCommandArgs
```

Note: `GeoSearchCommandArgs` is a **static inner class** of `RedisGeoCommands` in the `connection` package, not in `domain.geo`. The `geo.search()` overload that takes `GeoSearchCommandArgs` maps directly to the `GEOSEARCH` Redis command.

### 5.4 Redis GEO vs PostGIS — When to Use Each

| Concern                          | Redis GEO                        | PostGIS GiST                  |
|----------------------------------|----------------------------------|-------------------------------|
| **Latency**                      | Sub-millisecond (in-memory)      | 5–50ms (disk + index)         |
| **Complex shapes**               | Circle radius only               | Any geometry (polygon, line)  |
| **Update frequency**             | Designed for high-freq updates   | Higher write cost              |
| **Durability**                   | Configurable (AOF/RDB)           | Full ACID                     |
| **Geofencing (polygon)**         | No native support                | ST_Within, ST_Intersects       |
| **Best use case**                | Rider/driver location tracking   | Zone assignment, delivery areas|

This project combines both: Redis for sub-millisecond radius queries, PostGIS for polygon geofencing.

---

## 6. Geofencing with PostGIS

### 6.1 Delivery Zones as PostGIS Polygons

Zones are created as `GEOMETRY(Polygon, 4326)` using `ST_MakeEnvelope` (axis-aligned rectangles):

```sql
INSERT INTO delivery_zones (name, color, boundary) VALUES
  ('Lower Manhattan', '#2563eb',
   ST_MakeEnvelope(-74.025, 40.700, -73.965, 40.740, 4326));
--                   minLng   minLat  maxLng   maxLat  SRID
```

`ST_MakeEnvelope(xmin, ymin, xmax, ymax, srid)` creates a clockwise-wound polygon with 5 vertices (first = last, closing the ring):

```
(-74.025, 40.740) ──────────────── (-73.965, 40.740)
        │                                   │
        │          Lower Manhattan          │
        │                                   │
(-74.025, 40.700) ──────────────── (-73.965, 40.700)
```

### 6.2 ST_Within — Point-in-Polygon Test

```sql
SELECT dz.* FROM delivery_zones dz
WHERE ST_Within(
    CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geometry),
    dz.boundary
)
LIMIT 1;
```

**Function breakdown:**

| Function                              | What it does                                          |
|---------------------------------------|-------------------------------------------------------|
| `ST_MakePoint(lng, lat)`              | Creates a `POINT` geometry from coordinates           |
| `ST_SetSRID(geom, 4326)`              | Assigns SRID 4326 (WGS 84) to the geometry            |
| `CAST(... AS geometry)`               | Explicit cast (JPA-safe alternative to `::geometry`)  |
| `ST_Within(A, B)`                     | Returns true if A is completely inside B              |

**ST_Within vs ST_Contains:**
`ST_Within(A, B)` = `ST_Contains(B, A)`. The index on `boundary` is used when `ST_Contains(boundary, point)` is written; `ST_Within` may not trigger the index in all PG versions. For indexed lookups prefer `ST_Contains(dz.boundary, point)`.

**Why GIST index on boundary?**
`ST_Within` first performs a bounding-box overlap check (`&&`) which uses the GiST index, then the exact topology check. Without the index, every polygon is tested — O(n) full scan.

### 6.3 Zone Crossing Detection — State Machine in RiderService

```java
// In-memory state per rider (ConcurrentHashMap for thread safety)
Map<String, String> riderZoneMap;      // riderId → current zone name
Map<String, Long>   riderAssignmentMap; // riderId → current restaurant id

// On each update:
String previousZone = riderZoneMap.get(riderId);
String currentZone  = geofenceService.getZoneName(lat, lng).orElse(null);

if (currentZone != null && !currentZone.equals(previousZone)) {
    publish(GEOFENCE_ENTER, payload);   // entered a new zone
} else if (currentZone == null && previousZone != null) {
    publish(GEOFENCE_EXIT, payload);    // left all zones
}
riderZoneMap.put(riderId, currentZone);
```

This is a simple state machine with two states per zone per rider: INSIDE / OUTSIDE. Transitions emit SSE events.

---

## 7. SSE Real-Time Event Stream

### 7.1 Server-Sent Events vs Alternatives

| Transport       | Direction       | Protocol     | Use case                            |
|-----------------|-----------------|--------------|--------------------------------------|
| Polling         | Client → Server | HTTP         | Simple, high latency, wasteful       |
| Long polling    | Client ↔ Server | HTTP         | Moderate latency, complex server     |
| **SSE**         | Server → Client | HTTP/1.1+    | One-way push, auto-reconnect         |
| WebSocket       | Bidirectional   | WS           | Chat, games, bidirectional real-time |
| Kafka           | Pub/sub         | TCP          | Durable, distributed, high throughput|

SSE is optimal here because:
- Events only flow **server → client** (rider position updates, zone events)
- Native browser support via `EventSource` — no library needed
- Automatic reconnection built into the protocol
- Works over standard HTTP — no upgrade handshake like WebSocket
- Simple Spring `SseEmitter` API

### 7.2 EventStreamService — Fan-Out Design

```java
@Service
public class EventStreamService {

    // CopyOnWriteArrayList: thread-safe for "many reads, rare writes"
    // reads  = every publish() iterates the list  (frequent)
    // writes = subscribe/unsubscribe               (rare)
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(300_000L);  // 5-min timeout
        subscribers.add(emitter);

        // Auto-remove on disconnect (completion, timeout, error)
        Runnable cleanup = () -> subscribers.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    public void publish(EventType type, String jsonPayload) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                    .name(type.name())   // sets the SSE "event:" field
                    .data(jsonPayload)); // sets the "data:" field
            } catch (IOException e) {
                dead.add(emitter);      // broken connection
            }
        }
        subscribers.removeAll(dead);
    }
}
```

**SSE Wire Format:**

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache

event: GEOFENCE_ENTER
data: {"riderId":"rider-1","lat":40.740,"lng":-73.990,"zone":"Midtown Manhattan"}

event: RIDER_ASSIGNED
data: {"riderId":"rider-1","restaurantId":3,"restaurantName":"Le Bernardin","distanceMiles":0.31}

event: LOCATION_UPDATE
data: {"riderId":"rider-1","lat":40.741,"lng":-73.989,"zone":"Midtown Manhattan","nearbyCount":7}
```

### 7.3 Event Types

| Event Type          | Trigger Condition                                      | Payload                              |
|---------------------|--------------------------------------------------------|--------------------------------------|
| `LOCATION_UPDATE`   | Every rider tick (always)                              | riderId, lat, lng, zone, nearbyCount |
| `GEOFENCE_ENTER`    | Rider moves into a delivery zone                       | riderId, lat, lng, zone name         |
| `GEOFENCE_EXIT`     | Rider moves out of all delivery zones                  | riderId, lat, lng, last zone name    |
| `RIDER_ASSIGNED`    | Nearest restaurant ≤ 0.5 mi and was not assigned before| riderId, restaurantId, name, dist    |
| `RIDER_UNASSIGNED`  | Was assigned, now nearest > 0.5 mi                     | riderId, prevRestaurantId            |

### 7.4 Browser EventSource

```javascript
const source = new EventSource('/api/rider/stream');

source.addEventListener('GEOFENCE_ENTER', e => {
    const d = JSON.parse(e.data);
    console.log(`Entered zone: ${d.zone}`);
});

source.addEventListener('RIDER_ASSIGNED', e => {
    const d = JSON.parse(e.data);
    highlightAssigned(d.restaurantId);
});

// Auto-reconnects on network error (browser built-in)
source.onerror = () => { /* optional error UI */ };
```

---

## 8. Complete Rider Event Flow

The following sequence diagram shows one complete simulation tick — the full path from browser timer to SSE event delivery.

```
Browser (JS)          Spring (HTTP)       RiderService        Redis        PostgreSQL       Browser (SSE)
     │                      │                  │                │               │                 │
     │                      │                  │                │               │                 │
─────┼──── setInterval ─────┼──────────────────┼────────────────┼───────────────┼─────────────────┼──
     │                      │                  │                │               │                 │
     │  POST /api/rider/    │                  │                │               │                 │
     │──update {lat, lng}──►│                  │                │               │                 │
     │                      │                  │                │               │                 │
     │                      │─processUpdate()─►│                │               │                 │
     │                      │                  │                │               │                 │
     │                      │                  │── GEOSEARCH ──►│               │                 │
     │                      │                  │                │  scan sorted  │                 │
     │                      │                  │                │  set scores   │                 │
     │                      │                  │◄─[{id,dist}]──│               │                 │
     │                      │                  │  enrich from   │               │                 │
     │                      │                  │  restaurantCache               │                 │
     │                      │                  │                │               │                 │
     │                      │                  │── ST_Within ───────────────────►│                 │
     │                      │                  │  (geofence)    │               │ GiST idx scan   │
     │                      │                  │◄─ "Midtown" ────────────────────│                 │
     │                      │                  │                │               │                 │
     │                      │                  │  [zone changed?]               │                 │
     │                      │                  │  prev="Lower", curr="Midtown"  │                 │
     │                      │                  │                │               │                 │
     │                      │                  │── publish(GEOFENCE_EXIT, ...) ──────────────────►│
     │                      │                  │                │               │  event: GEOFENCE_EXIT
     │                      │                  │                │               │  data: {...}    │
     │                      │                  │                │               │                 │
     │                      │                  │── publish(GEOFENCE_ENTER, ...) ─────────────────►│
     │                      │                  │                │               │  event: GEOFENCE_ENTER
     │                      │                  │                │               │  data: {"zone":"Midtown"}
     │                      │                  │                │               │                 │
     │                      │                  │  [nearest < 0.5 mi?]           │                 │
     │                      │                  │  Le Bernardin: 0.31 mi         │                 │
     │                      │                  │                │               │                 │
     │                      │                  │── publish(RIDER_ASSIGNED, ...) ─────────────────►│
     │                      │                  │                │               │  event: RIDER_ASSIGNED
     │                      │                  │                │               │  data: {"restaurantName":
     │                      │                  │                │               │         "Le Bernardin"}
     │                      │                  │                │               │                 │
     │                      │                  │── publish(LOCATION_UPDATE, ...) ────────────────►│
     │                      │                  │                │               │  event: LOCATION_UPDATE
     │                      │                  │                │               │  data: {...}    │
     │                      │                  │                │               │                 │
     │◄─── RiderUpdateResponse ───────────────│                │               │                 │
     │  {currentZone, assignedRestaurantId,   │                │               │                 │
     │   nearbyRestaurants, executionTimeMs}  │                │               │                 │
     │                      │                  │                │               │                 │
     │  update map markers   │                  │               │               │                 │
     │  gold star on assigned│                  │               │               │                 │
     │  zone badge in UI     │                  │               │               │                 │
     │  event log entry      │                  │               │               │                 │
─────┼──────────────────────┼──────────────────┼────────────────┼───────────────┼─────────────────┼──
     │                (next tick ~1500ms)       │                │               │                 │
```

**Two channels for one tick:**

1. **HTTP response** (`POST /api/rider/update`) — returns the full state snapshot synchronously. The browser uses this to update map markers and the sidebar list.
2. **SSE push** (open `GET /api/rider/stream` connection) — events are pushed independently to all connected tabs for state transitions (zone changes, assignments).

This dual-channel design means the HTTP response is sufficient to keep one tab in sync, while SSE enables multiple tabs or future backend consumers to react to events without polling.

---

## 9. PostGIS Function Reference

### Core Geometry Construction

**`ST_MakePoint(lng, lat)`**
```sql
SELECT ST_MakePoint(-74.006, 40.7128);
-- Returns: POINT(-74.006 40.7128)
-- Note: longitude FIRST, latitude second (GIS/WKT convention)
-- Contrast with most mapping APIs which use (lat, lng)
```

**`ST_SetSRID(geometry, srid)`**
```sql
SELECT ST_SetSRID(ST_MakePoint(-74.006, 40.7128), 4326);
-- SRID 4326 = WGS 84 coordinate reference system
-- The SRID tag is required for geography casts and spatial operations
-- to produce accurate results in metres
```

**`ST_MakeEnvelope(xmin, ymin, xmax, ymax, srid)`**
```sql
SELECT ST_MakeEnvelope(-74.025, 40.700, -73.965, 40.740, 4326);
-- Creates a Polygon rectangle: 5 points (ring is closed)
-- xmin = west longitude, ymin = south latitude
-- xmax = east longitude, ymax = north latitude
-- Equivalent to: ST_GeomFromText('POLYGON((-74.025 40.7, -73.965 40.7,
--   -73.965 40.74, -74.025 40.74, -74.025 40.7))', 4326)
```

---

### Distance & Containment

**`ST_DWithin(geom_a, geom_b, distance)`**
```sql
-- GEOMETRY mode: distance in degrees
SELECT ST_DWithin(point_geom, center_geom, 0.045);

-- GEOGRAPHY mode: distance in metres (use this for accuracy)
SELECT ST_DWithin(
    CAST(point_geom AS geography),
    CAST(center_geom AS geography),
    8046.72   -- 5 miles in metres (1 mile = 1609.344 m)
);
-- Activates GiST index via bounding-box pre-filter, then exact check
-- TRUE if the two geometries are within [distance] of each other
```

**`ST_Distance(geom_a, geom_b)`**
```sql
-- Returns distance (degrees for geometry, metres for geography)
SELECT ST_Distance(
    CAST(r.location_gist AS geography),
    CAST(ST_MakePoint(:lng, :lat) AS geography)
) / 1609.344 AS distance_miles
FROM restaurants r;
```

**`ST_Within(geometry_a, geometry_b)`**
```sql
-- Returns TRUE if A is completely contained within B
-- Used for geofencing: is the rider's point inside a delivery zone polygon?
SELECT ST_Within(
    CAST(ST_SetSRID(ST_MakePoint(lng, lat), 4326) AS geometry),
    zone.boundary
);
-- Does NOT automatically use the index on boundary.
-- For index usage, prefer: ST_Contains(zone.boundary, point)
-- ST_Within(A,B) ≡ ST_Contains(B,A)
```

**`ST_Contains(geometry_a, geometry_b)`**
```sql
-- Returns TRUE if A completely contains B
-- PostGIS can use a GiST index on geometry_a for this form
SELECT ST_Contains(zone.boundary, point);
-- GiST index on zone.boundary → used via && pre-filter
```

---

### Bounding Box & Expansion

**`ST_Expand(geometry, distance_degrees)`**
```sql
-- Expands a geometry's bounding box by [distance] in all directions
-- Used in SP-GiST queries to create a search envelope
SELECT ST_Expand(ST_MakePoint(-74.006, 40.7128), 0.09);
-- 0.09 degrees ≈ 10 km at NYC latitude
-- Returns a rectangle (Polygon) centered on the input point
-- This rectangle is used with the && operator to activate SP-GiST index
```

**`&&` operator (bounding box overlap)**
```sql
-- Returns TRUE if the bounding boxes of two geometries overlap
-- This is the *index-activating* operator for GiST and SP-GiST
WHERE location_spgist && ST_Expand(center, 0.09)
-- SP-GiST index on location_spgist is used here
-- Much faster than a full geometry test — just rectangle comparison
```

---

### Type Casting

**`CAST(expr AS geography)`**
```sql
-- Converts GEOMETRY to GEOGRAPHY type
-- GEOGRAPHY uses an ellipsoidal model (WGS 84 spheroid)
-- Distance units become metres, accurate globally
-- Required for ST_DWithin with metre thresholds

-- Safe in Spring Data JPA native queries:
CAST(location_gist AS geography)

-- DO NOT use PostgreSQL shorthand in @Query — JPA parser breaks on colon:
location_gist::geography   -- ← BROKEN in Spring Data JPA named params
```

**SRID 4326 — WGS 84**
```
EPSG:4326 = World Geodetic System 1984
- The standard GPS coordinate system
- Latitude: -90 to +90 (south to north)
- Longitude: -180 to +180 (west to east)
- Used by Google Maps, OpenStreetMap, GPS receivers
```

---

### Index Inspection

**Check what indexes exist on a table:**
```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'restaurants';

-- Output:
-- idx_restaurants_gist    | CREATE INDEX ... USING gist (location_gist)
-- idx_restaurants_spgist  | CREATE INDEX ... USING spgist (location_spgist)
-- idx_restaurants_geohash | CREATE INDEX ... (geohash)
```

**Force a query to use a specific index (testing):**
```sql
SET enable_seqscan = OFF;    -- force index usage
EXPLAIN ANALYZE SELECT ...;  -- see the execution plan
SET enable_seqscan = ON;     -- restore default
```

**EXPLAIN ANALYZE output for GiST query:**
```
Index Scan using idx_restaurants_gist on restaurants
  (cost=0.14..8.42 rows=1 width=64) (actual rows=9 loops=1)
  Index Cond: (location_gist && ...)      ← bbox pre-filter used index
  Filter: st_dwithin(location_gist::geography, ..., 8046.72)
  Rows Removed by Filter: 2              ← 2 false positives from bbox
```

---

## 10. System Design Tradeoffs

### 10.1 Why In-Memory Cache Instead of Redis Hash?

The `restaurantCache` stores all 23 restaurant objects in the JVM heap. Alternatives:

| Option                           | Latency        | Consistency      | Complexity |
|----------------------------------|----------------|------------------|------------|
| JVM HashMap (current)            | ~0 ns          | Stale on restart | Low        |
| Redis HSET (restaurant metadata) | ~0.5ms network | Stale until evict| Medium     |
| PostgreSQL lookup per result     | ~5ms per result| Always fresh     | Low        |

For this scale (23 restaurants, rarely updated), JVM cache wins. At scale (millions of restaurants), a Redis Hash or a CDN-cached HTTP endpoint would be appropriate.

### 10.2 ConcurrentHashMap vs Distributed State

Rider state (`riderZoneMap`, `riderAssignmentMap`) lives in a single JVM's `ConcurrentHashMap`. This works for one app instance.

**For horizontal scaling:**
- Move rider state to Redis: `HSET rider:rider-1 zone "Midtown"`, `HSET rider:rider-1 assigned "3"`
- Use Redis pub/sub or Kafka for cross-instance SSE fan-out
- Each instance maintains its own `SseEmitter` list; events broadcast via Redis pub/sub

```
Instance A                     Instance B
SseEmitters: [tab1, tab2]     SseEmitters: [tab3]

RiderService (A) publishes ──► Redis Pub/Sub ──► Instance B subscribes
                                                 broadcasts to [tab3]
```

### 10.3 SSE Scalability Ceiling

Each open SSE connection holds an HTTP thread (or a Servlet async context). With Tomcat's default 200 threads:
- 200 concurrent SSE clients saturate the thread pool
- Mitigation: Switch to reactive stack (`WebFlux` + `Flux<ServerSentEvent>`) — each SSE connection uses a tiny event loop task, not a thread
- At very large scale, offload to a purpose-built push service (Pusher, Ably, Firebase Realtime DB)

### 10.4 The `GEORADIUS` → `GEOSEARCH` Migration

Redis deprecated `GEORADIUS` in Redis 6.2 (released 2021). The replacement `GEOSEARCH` command provides:
- `FROMLONLAT` — search from a coordinate (equivalent to old GEORADIUS)
- `FROMMEMBER` — search from an existing member's position
- `BYBOX` — rectangular area search (new, not available in GEORADIUS)
- `BYRADIUS` — circle search (same as GEORADIUS)

In Spring Data Redis 3.2.3, the mapping is:
- `geo.radius()` + `Circle` + `GeoRadiusCommandArgs` → **deprecated**, maps to `GEORADIUS`
- `geo.search()` + `RadiusShape` + `GeoSearchCommandArgs` → **current**, maps to `GEOSEARCH`

### 10.5 PostGIS `&&` and the Dual-Filter Pattern

Every PostGIS spatial query uses a **two-step** filter:

```
Step 1: Bounding-box filter (cheap)
        Uses GiST/SP-GiST index
        Produces a candidate set — may include false positives

Step 2: Exact geometry test (expensive)
        Applied only to candidates from Step 1
        Eliminates false positives
```

This pattern exists because exact spatial predicates (`ST_DWithin`, `ST_Within`) are computationally expensive. The index narrows the working set to a few hundred rows at most; the exact test runs on a tiny fraction of the table. Index operators like `&&` are called **lossy** — they accept false positives but no false negatives.

### 10.6 SRID and Coordinate System Pitfalls

Common bugs:
- **Mixed SRIDs:** `ST_DWithin(geom_4326, geom_4269, 8000)` silently produces wrong results if SRIDs differ
- **Degrees vs metres:** `ST_DWithin(geom, geom, 5)` on GEOMETRY type checks 5 **degrees** (~550 km), not 5 metres
- **Longitude/latitude order:** PostGIS uses `(lng, lat)` order, most mapping APIs use `(lat, lng)`. `ST_MakePoint(-74.006, 40.712)` is correct; `ST_MakePoint(40.712, -74.006)` silently places the point off the coast of Antarctica

Always:
1. Store with explicit SRID: `ST_SetSRID(ST_MakePoint(lng, lat), 4326)`
2. Cast to GEOGRAPHY for metre-based distance: `CAST(geom AS geography)`
3. Verify coordinate order in every `ST_MakePoint` call

---

*Built with Spring Boot 3.2.3 · PostgreSQL 16 · PostGIS 3.4 · Redis 7 · Hibernate Spatial 6.4*
