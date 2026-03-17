package com.example.simplelocator.service;

import com.example.simplelocator.dto.*;
import com.example.simplelocator.entity.Restaurant;
import com.example.simplelocator.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private static final double MILES_TO_METERS      = 1609.344;
    private static final double SEARCH_RADIUS_MILES   = 5.0;
    private static final double SEARCH_RADIUS_METERS  = SEARCH_RADIUS_MILES * MILES_TO_METERS; // 8046.72m
    private static final double SPGIST_BUFFER_DEGREES = 0.09;   // ~10km envelope
    private static final double MBR_PADDING           = 0.005;  // ~550m padding on each MBR side

    private final RestaurantRepository restaurantRepository;
    private final GeohashService       geohashService;

    // ──────────────────────────────────────────────────────────────────────────
    // 1. GiST (R-tree)
    // ──────────────────────────────────────────────────────────────────────────
    public SearchResult searchWithGist(LocationRequest req) {
        long start = System.currentTimeMillis();
        List<Object[]> rows = restaurantRepository.findWithinRadiusUsingGist(
                req.getLatitude(), req.getLongitude(), SEARCH_RADIUS_METERS);
        long elapsed = System.currentTimeMillis() - start;

        return SearchResult.builder()
                .indexType("GiST (R-tree)")
                .description("GiST implements an R-tree in PostGIS. Geometries are grouped into " +
                        "hierarchical bounding boxes. ST_DWithin() walks the tree and prunes " +
                        "branches whose MBR cannot intersect the search circle. Best all-around " +
                        "spatial index — distance, overlap, contains.")
                .count(rows.size()).executionTimeMs(elapsed)
                .restaurants(rows.stream().map(this::mapRow).toList())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. SP-GiST (Quadtree)
    // ──────────────────────────────────────────────────────────────────────────
    public SearchResult searchWithSpgist(LocationRequest req) {
        long start = System.currentTimeMillis();
        List<Object[]> rows = restaurantRepository.findWithinRadiusUsingSpgist(
                req.getLatitude(), req.getLongitude(), SEARCH_RADIUS_METERS, SPGIST_BUFFER_DEGREES);
        long elapsed = System.currentTimeMillis() - start;

        return SearchResult.builder()
                .indexType("SP-GiST (Quadtree)")
                .description("SP-GiST implements a Quadtree in PostGIS. Space is recursively " +
                        "split into 4 equal non-overlapping quadrants. The && bounding-box " +
                        "operator navigates the quadrant hierarchy; ST_DWithin does the exact " +
                        "circle filter. Best for uniformly distributed point data.")
                .count(rows.size()).executionTimeMs(elapsed)
                .restaurants(rows.stream().map(this::mapRow).toList())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. Geohash (B-tree)
    // ──────────────────────────────────────────────────────────────────────────
    public SearchResult searchWithGeohash(LocationRequest req) {
        List<String> prefixes     = geohashService.getPrefixesForSearch(req.getLatitude(), req.getLongitude());
        List<String> cleanPrefixes = cleanPrefixes(prefixes);

        long start = System.currentTimeMillis();
        List<Object[]> rows = restaurantRepository.findWithinRadiusUsingGeohash(
                req.getLatitude(), req.getLongitude(), SEARCH_RADIUS_METERS,
                cleanPrefixes.get(0), cleanPrefixes.get(1), cleanPrefixes.get(2),
                cleanPrefixes.get(3), cleanPrefixes.get(4), cleanPrefixes.get(5),
                cleanPrefixes.get(6), cleanPrefixes.get(7), cleanPrefixes.get(8));
        long elapsed = System.currentTimeMillis() - start;

        return SearchResult.builder()
                .indexType("Geohash (B-tree)")
                .description("Geohash encodes lat/lng as a base-32 string; nearby locations " +
                        "share common prefixes. Center + 8 neighbors at precision-4 give 9 cells. " +
                        "LIKE 'prefix%' on a B-tree index fetches candidates; ST_DWithin removes " +
                        "false positives from cell corners. Works in Redis, DynamoDB, any store " +
                        "with a string index.")
                .count(rows.size()).executionTimeMs(elapsed)
                .restaurants(rows.stream().map(this::mapRow).toList())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Compare all three
    // ──────────────────────────────────────────────────────────────────────────
    public Map<String, SearchResult> compareAll(LocationRequest req) {
        return Map.of(
                "gist",    searchWithGist(req),
                "spgist",  searchWithSpgist(req),
                "geohash", searchWithGeohash(req)
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Debug / Visualization endpoint
    // Returns all spatial metadata the frontend needs to render Leaflet maps.
    // ──────────────────────────────────────────────────────────────────────────
    public DebugInfoDto getDebugInfo(LocationRequest req) {
        double lat = req.getLatitude();
        double lng = req.getLongitude();

        // 1. Run all three searches to collect timings and result IDs
        SearchResult gistResult    = searchWithGist(req);
        SearchResult spgistResult  = searchWithSpgist(req);
        SearchResult geohashResult = searchWithGeohash(req);

        Set<Long> withinRadiusIds = gistResult.getRestaurants().stream()
                .map(RestaurantResponse::getId).collect(Collectors.toSet());

        // 2. Geohash cells for the search
        List<GeohashCellDto> cells        = geohashService.getCellBboxes(lat, lng);
        List<String>          cellPrefixes = cells.stream().map(GeohashCellDto::getPrefix).toList();

        // 3. SP-GiST search bounding box (mirrors ST_Expand in SQL)
        BboxDto searchBbox = BboxDto.builder()
                .minLat(lat - SPGIST_BUFFER_DEGREES).maxLat(lat + SPGIST_BUFFER_DEGREES)
                .minLng(lng - SPGIST_BUFFER_DEGREES).maxLng(lng + SPGIST_BUFFER_DEGREES)
                .build();

        // 4. Simulated R-tree MBR groups (quadrant clustering of all data)
        List<Restaurant>  all       = restaurantRepository.findAll();
        List<MbrGroupDto> mbrGroups = computeMbrGroups(all, lat, lng);

        // 5. Build per-restaurant debug records
        List<RestaurantDebugDto> debugList = all.stream().map(r -> {
            double distM  = haversineMeters(lat, lng, r.getLatitude(), r.getLongitude());
            double distMi = Math.round(distM / MILES_TO_METERS * 100.0) / 100.0;

            // SP-GiST: inside bounding box?
            boolean inBbox = r.getLatitude()  >= searchBbox.getMinLat()
                          && r.getLatitude()  <= searchBbox.getMaxLat()
                          && r.getLongitude() >= searchBbox.getMinLng()
                          && r.getLongitude() <= searchBbox.getMaxLng();

            // Geohash: which cell (if any) contains this restaurant?
            String matchedCell = null;
            boolean cellIsCenter = false;
            if (r.getGeohash() != null) {
                for (int i = 0; i < cellPrefixes.size(); i++) {
                    if (r.getGeohash().startsWith(cellPrefixes.get(i))) {
                        matchedCell  = cellPrefixes.get(i);
                        cellIsCenter = (i == 0); // index 0 = center cell
                        break;
                    }
                }
            }

            // GiST MBR: which group?
            String mbrName = null, mbrColor = null;
            for (MbrGroupDto g : mbrGroups) {
                BboxDto b = g.getBbox();
                if (r.getLatitude()  >= b.getMinLat() && r.getLatitude()  <= b.getMaxLat()
                 && r.getLongitude() >= b.getMinLng() && r.getLongitude() <= b.getMaxLng()) {
                    mbrName  = g.getName();
                    mbrColor = g.getColor();
                    break;
                }
            }

            return RestaurantDebugDto.builder()
                    .id(r.getId()).name(r.getName()).cuisine(r.getCuisine())
                    .latitude(r.getLatitude()).longitude(r.getLongitude())
                    .distanceMiles(distMi).geohash(r.getGeohash())
                    .withinRadius(withinRadiusIds.contains(r.getId()))
                    .withinBbox(inBbox)
                    .geohashCell(matchedCell).geohashCellIsCenter(cellIsCenter)
                    .mbrGroup(mbrName).mbrGroupColor(mbrColor)
                    .build();
        }).toList();

        return DebugInfoDto.builder()
                .centerLat(lat).centerLng(lng)
                .radiusMeters(SEARCH_RADIUS_METERS)
                .bufferDegrees(SPGIST_BUFFER_DEGREES)
                .searchBbox(searchBbox)
                .geohashCells(cells)
                .mbrGroups(mbrGroups)
                .restaurants(debugList)
                .gistMs(gistResult.getExecutionTimeMs())
                .spgistMs(spgistResult.getExecutionTimeMs())
                .geohashMs(geohashResult.getExecutionTimeMs())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Divides all restaurants into 4 quadrants (NW/NE/SW/SE) relative to the
     * data centroid, then computes a tight MBR + padding for each quadrant.
     * These simulate what an R-tree's level-1 nodes might look like.
     */
    private List<MbrGroupDto> computeMbrGroups(List<Restaurant> all, double searchLat, double searchLng) {
        if (all.isEmpty()) return List.of();

        double centLat = all.stream().mapToDouble(Restaurant::getLatitude).average().getAsDouble();
        double centLng = all.stream().mapToDouble(Restaurant::getLongitude).average().getAsDouble();

        record Group(String quad, String name, String color) {}
        List<Group> defs = List.of(
                new Group("NW", "North-West MBR", "#3b82f6"),
                new Group("NE", "North-East MBR", "#10b981"),
                new Group("SW", "South-West MBR", "#f59e0b"),
                new Group("SE", "South-East MBR", "#ef4444")
        );

        Map<String, List<Restaurant>> byQuad = new LinkedHashMap<>();
        for (Restaurant r : all) {
            String q = (r.getLatitude() >= centLat ? "N" : "S")
                     + (r.getLongitude() >= centLng ? "E" : "W");
            byQuad.computeIfAbsent(q, k -> new ArrayList<>()).add(r);
        }

        List<MbrGroupDto> result = new ArrayList<>();
        for (Group def : defs) {
            List<Restaurant> grp = byQuad.getOrDefault(def.quad(), List.of());
            if (grp.isEmpty()) continue;

            double minLat = grp.stream().mapToDouble(Restaurant::getLatitude).min().getAsDouble()  - MBR_PADDING;
            double maxLat = grp.stream().mapToDouble(Restaurant::getLatitude).max().getAsDouble()  + MBR_PADDING;
            double minLng = grp.stream().mapToDouble(Restaurant::getLongitude).min().getAsDouble() - MBR_PADDING;
            double maxLng = grp.stream().mapToDouble(Restaurant::getLongitude).max().getAsDouble() + MBR_PADDING;

            BboxDto bbox = BboxDto.builder()
                    .minLat(minLat).maxLat(maxLat).minLng(minLng).maxLng(maxLng).build();

            result.add(MbrGroupDto.builder()
                    .name(def.name()).color(def.color()).bbox(bbox)
                    .intersectsSearch(mbrIntersectsCircle(bbox, searchLat, searchLng, SEARCH_RADIUS_METERS))
                    .pointCount(grp.size())
                    .build());
        }
        return result;
    }

    /** Haversine distance between two WGS-84 points in metres. */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6_371_000;
        double phi1  = Math.toRadians(lat1), phi2  = Math.toRadians(lat2);
        double dPhi  = Math.toRadians(lat2 - lat1);
        double dLam  = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2) * Math.sin(dLam / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Returns true if the axis-aligned MBR intersects a circle (nearest-point test). */
    private static boolean mbrIntersectsCircle(BboxDto bbox, double lat, double lng, double radiusM) {
        double nearLat = Math.max(bbox.getMinLat(), Math.min(lat, bbox.getMaxLat()));
        double nearLng = Math.max(bbox.getMinLng(), Math.min(lng, bbox.getMaxLng()));
        return haversineMeters(lat, lng, nearLat, nearLng) <= radiusM;
    }

    /** Strips trailing '%' from LIKE patterns (repository expects raw prefix strings). */
    private static List<String> cleanPrefixes(List<String> prefixes) {
        return prefixes.stream()
                .map(p -> p.endsWith("%") ? p.substring(0, p.length() - 1) : p)
                .toList();
    }

    /** Maps a native Object[] row to RestaurantResponse. Column order must match the native query SELECT. */
    private RestaurantResponse mapRow(Object[] row) {
        double distanceMeters = toDouble(row[9]);
        double distanceMiles  = distanceMeters / MILES_TO_METERS;
        return RestaurantResponse.builder()
                .id(toLong(row[0])).name((String) row[1]).address((String) row[2])
                .cuisine((String) row[3]).latitude(toDouble(row[4])).longitude(toDouble(row[5]))
                .geohash((String) row[8])
                .distanceMiles(Math.round(distanceMiles * 100.0) / 100.0)
                .build();
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n)    return n.doubleValue();
        if (o instanceof BigDecimal b) return b.doubleValue();
        return 0.0;
    }
    private static long toLong(Object o) {
        if (o instanceof Number n)    return n.longValue();
        if (o instanceof BigInteger b) return b.longValue();
        return 0L;
    }
}
