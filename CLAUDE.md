# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Prerequisites:** Docker must be running with the PostGIS container before starting the app.

```bash
# Start the database
docker-compose up -d

# Compile
powershell.exe -Command "& './mvnw.cmd' compile"

# Run the application
powershell.exe -Command "& './mvnw.cmd' spring-boot:run"

# Run tests
powershell.exe -Command "& './mvnw.cmd' test"

# Run a single test class
powershell.exe -Command "& './mvnw.cmd' test -Dtest=RestaurantServiceTest"

# Kill any process holding port 8080 before restarting
powershell.exe -Command "Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess"
powershell.exe -Command "Stop-Process -Id <PID> -Force"
```

App runs at `http://localhost:8080`. pgAdmin at `http://localhost:5050` (admin@simplelocator.com / admin123).

## JDK 25 Compatibility (Critical)

Spring Boot 3.2.3 defaults break on JDK 25. Two version overrides in `pom.xml` are **required**:

```xml
<lombok.version>1.18.44</lombok.version>
<maven-compiler-plugin.version>3.13.0</maven-compiler-plugin.version>
```

Lombok also requires explicit `annotationProcessorPaths` in the compiler plugin (already configured). Do **not** add `flyway-database-postgresql` as a dependency — it is only needed for Flyway 10+; Spring Boot 3.2.x ships Flyway 9.x which bundles PostgreSQL support in `flyway-core`.

## Architecture

The app demonstrates three PostGIS geospatial index strategies for finding restaurants within 5 miles of a given coordinate.

### Request Flow

```
Browser → GET /           → WebController → index.html (Thymeleaf)
Browser → POST /api/...   → RestaurantController → RestaurantService → RestaurantRepository (native SQL)
```

**Two API paths:**
- `/api/restaurants/search/compare` — runs all three searches and returns timing + results for the UI cards
- `/api/restaurants/search/debug` — runs all three searches plus computes spatial metadata (MBR groups, geohash cells, bbox) for rendering the Leaflet visualization maps

### The Three Index Strategies

Each strategy has a dedicated native SQL query in `RestaurantRepository` and a dedicated geometry column in `restaurants`:

| Strategy | Column | Index | SQL Pattern |
|---|---|---|---|
| GiST (R-tree) | `location_gist` | `USING GIST` | `ST_DWithin(CAST(... AS geography), ...)` |
| SP-GiST (Quadtree) | `location_spgist` | `USING SPGIST` | `location_spgist && ST_Expand(...)` then `ST_DWithin` |
| Geohash (B-tree) | `geohash VARCHAR(12)` | standard B-tree | `geohash LIKE CONCAT(:p0,'%') OR ...` (9 prefixes) then `ST_DWithin` |

Two separate geometry columns exist intentionally so PostgreSQL's query planner unambiguously picks the correct index for each query.

**IMPORTANT:** Never use the PostgreSQL `::` cast operator inside Spring Data JPA `@Query(nativeQuery=true)`. Spring's `:paramName` parser strips one colon, breaking the syntax. Always use ANSI SQL `CAST(expr AS type)` instead.

### Geohash Search Logic (`GeohashService`)

Search precision is 4 (≈39 km × 20 km cells). A query generates 9 cells — the center cell plus its 8 neighbors — to guarantee 5-mile coverage regardless of where the query point sits within its cell. Stored geohashes use precision 9 (≈4 m × 5 m) computed via `ST_GeoHash(..., 9)` in the migration SQL.

### Debug/Visualization Endpoint (`/search/debug`)

`RestaurantService.getDebugInfo()` returns `DebugInfoDto` containing:
- `geohashCells` — 9 cells with bounding boxes (from `GeohashService.getCellBboxes()`)
- `mbrGroups` — 4 simulated R-tree MBR groups (NW/NE/SW/SE quadrants of data centroid) computed in `computeMbrGroups()`
- `searchBbox` — the `ST_Expand` envelope used by SP-GiST
- `restaurants` — all 23 rows annotated with `withinRadius`, `withinBbox`, `geohashCell`, `mbrGroup` flags

The frontend (`app.js`) uses this response to render three Leaflet map tabs showing exactly how each index prunes or includes data points.

### `mapRow()` Column Order

`RestaurantService.mapRow(Object[] row)` maps native query results by positional index. Column order in all three SELECT statements must stay consistent: `id[0], name[1], address[2], cuisine[3], latitude[4], longitude[5], location_gist[6], location_spgist[7], geohash[8], dist_meters[9]`.

### Database Schema

Managed by Flyway (`ddl-auto: validate` — Hibernate never modifies the schema):
- `V1` — creates `restaurants` table with `location_gist`, `location_spgist` geometry columns and `geohash` varchar
- `V2` — creates the three indexes (GiST, SP-GiST, B-tree)
- `V3` — inserts 23 NYC-area sample restaurants; geometry values use `ST_SetSRID(ST_MakePoint(lng,lat),4326)`, geohash via `ST_GeoHash(...,9)`
