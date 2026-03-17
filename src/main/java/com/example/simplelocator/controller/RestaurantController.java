package com.example.simplelocator.controller;

import com.example.simplelocator.dto.DebugInfoDto;
import com.example.simplelocator.dto.LocationRequest;
import com.example.simplelocator.dto.SearchResult;
import com.example.simplelocator.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API to demonstrate three geospatial index strategies
 * for finding restaurants within 5 miles of the user's location.
 *
 * POST body for all endpoints:
 * {
 *   "latitude":  40.7128,
 *   "longitude": -74.0060
 * }
 */
@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    /**
     * Search using GiST (R-tree) index.
     * Uses ST_DWithin on a geometry column indexed with USING GIST.
     */
    @PostMapping("/search/gist")
    public ResponseEntity<SearchResult> searchWithGist(@RequestBody LocationRequest req) {
        return ResponseEntity.ok(restaurantService.searchWithGist(req));
    }

    /**
     * Search using SP-GiST (Quadtree) index.
     * Uses && bounding-box operator on a geometry column indexed with USING SPGIST,
     * followed by exact ST_DWithin filtering.
     */
    @PostMapping("/search/spgist")
    public ResponseEntity<SearchResult> searchWithSpgist(@RequestBody LocationRequest req) {
        return ResponseEntity.ok(restaurantService.searchWithSpgist(req));
    }

    /**
     * Search using Geohash B-tree index.
     * Computes 9 geohash prefixes (center + 8 neighbors) and uses LIKE ANY(...)
     * on a VARCHAR column indexed with a standard B-tree, then exact ST_DWithin.
     */
    @PostMapping("/search/geohash")
    public ResponseEntity<SearchResult> searchWithGeohash(@RequestBody LocationRequest req) {
        return ResponseEntity.ok(restaurantService.searchWithGeohash(req));
    }

    /**
     * Run all three index strategies and return a side-by-side comparison
     * including result count and execution time for each method.
     */
    @PostMapping("/search/compare")
    public ResponseEntity<Map<String, SearchResult>> compareAll(@RequestBody LocationRequest req) {
        return ResponseEntity.ok(restaurantService.compareAll(req));
    }

    /**
     * Returns all spatial metadata needed to render the three Leaflet visualization maps:
     *   - Simulated R-tree MBR groups (GiST)
     *   - Search bounding box / ST_Expand envelope (SP-GiST)
     *   - Geohash cells with bounding boxes (Geohash)
     *   - All restaurants annotated with withinRadius / withinBbox / geohashCell flags
     */
    @PostMapping("/search/debug")
    public ResponseEntity<DebugInfoDto> debugInfo(@RequestBody LocationRequest req) {
        return ResponseEntity.ok(restaurantService.getDebugInfo(req));
    }
}
