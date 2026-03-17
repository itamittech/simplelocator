package com.example.simplelocator.controller;

import com.example.simplelocator.dto.NearbyRestaurant;
import com.example.simplelocator.dto.RiderRequest;
import com.example.simplelocator.dto.RiderSearchResult;
import com.example.simplelocator.service.RedisGeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rider-mode API backed by Redis GEO commands.
 *
 * POST /api/rider/nearby   — GEOSEARCH by current rider location
 * GET  /api/rider/restaurants — full restaurant list for map initialisation
 */
@RestController
@RequestMapping("/api/rider")
@RequiredArgsConstructor
public class RiderController {

    private static final double DEFAULT_RADIUS_MILES = 2.0;

    private final RedisGeoService redisGeoService;

    /**
     * Find restaurants within {@code radiusMiles} (default 2) of the rider's
     * current position using Redis GEOSEARCH.
     */
    @PostMapping("/nearby")
    public ResponseEntity<RiderSearchResult> findNearby(@RequestBody RiderRequest req) {
        double radius = (req.getRadiusMiles() != null && req.getRadiusMiles() > 0)
                ? req.getRadiusMiles()
                : DEFAULT_RADIUS_MILES;
        return ResponseEntity.ok(
                redisGeoService.findNearby(req.getLatitude(), req.getLongitude(), radius));
    }

    /**
     * Returns all restaurants (id, name, cuisine, lat, lng) from the Redis
     * in-memory cache — used once on map initialisation to pre-plot all points.
     */
    @GetMapping("/restaurants")
    public ResponseEntity<List<NearbyRestaurant>> allRestaurants() {
        return ResponseEntity.ok(redisGeoService.getAllRestaurants());
    }
}
