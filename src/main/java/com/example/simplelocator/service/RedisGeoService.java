package com.example.simplelocator.service;

import com.example.simplelocator.dto.NearbyRestaurant;
import com.example.simplelocator.dto.RiderSearchResult;
import com.example.simplelocator.entity.Restaurant;
import com.example.simplelocator.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.RadiusShape;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages a Redis GEO sorted set for real-time rider proximity searches.
 *
 * Data structure: Sorted Set  key = "restaurants:geo"
 *   member = restaurant ID (String)
 *   score  = 52-bit geohash derived from (longitude, latitude)
 *
 * Redis encodes coordinates into a 52-bit integer geohash as the sorted-set
 * score. GEOSEARCH then does a radius scan entirely in memory — no SQL, no
 * geometry types — making it ideal for high-frequency location updates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisGeoService {

    static final String GEO_KEY = "restaurants:geo";

    private final StringRedisTemplate    stringRedisTemplate;
    private final RestaurantRepository   restaurantRepository;

    /**
     * In-memory metadata cache (id → Restaurant).
     * Populated alongside the Redis geo set so geo results can be enriched
     * with name/cuisine without a second round-trip to PostgreSQL or Redis.
     */
    private final Map<Long, Restaurant> restaurantCache = new HashMap<>();

    // ── Startup loading ───────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void loadRestaurantsIntoRedis() {
        try {
            GeoOperations<String, String> geo = stringRedisTemplate.opsForGeo();
            stringRedisTemplate.delete(GEO_KEY);
            restaurantCache.clear();

            List<Restaurant> all = restaurantRepository.findAll();
            for (Restaurant r : all) {
                // Redis GEO convention: Point(longitude, latitude)
                geo.add(GEO_KEY,
                        new Point(r.getLongitude(), r.getLatitude()),
                        String.valueOf(r.getId()));
                restaurantCache.put(r.getId(), r);
            }
            log.info("Loaded {} restaurants into Redis geo set '{}'", all.size(), GEO_KEY);
        } catch (Exception e) {
            log.error("Failed to load restaurants into Redis (is Redis running?): {}", e.getMessage());
        }
    }

    // ── Rider proximity search ────────────────────────────────────────────────

    /**
     * GEORADIUS restaurants:geo lng lat radiusMiles mi
     *          ASC WITHDIST COUNT 20
     */
    public RiderSearchResult findNearby(double lat, double lng, double radiusMiles) {
        long start = System.currentTimeMillis();
        try {
            GeoOperations<String, String> geo = stringRedisTemplate.opsForGeo();

            GeoResults<GeoLocation<String>> results = geo.search(
                    GEO_KEY,
                    GeoReference.fromCoordinate(new Point(lng, lat)),
                    new RadiusShape(new Distance(radiusMiles, Metrics.MILES)),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(20)
            );

            long elapsed = System.currentTimeMillis() - start;
            List<NearbyRestaurant> nearby = new ArrayList<>();

            if (results != null) {
                for (GeoResult<GeoLocation<String>> gr : results) {
                    Long id = Long.parseLong(gr.getContent().getName());
                    Restaurant r = restaurantCache.get(id);
                    if (r != null) {
                        double distMiles = Math.round(gr.getDistance().getValue() * 100.0) / 100.0;
                        nearby.add(NearbyRestaurant.builder()
                                .id(r.getId()).name(r.getName()).cuisine(r.getCuisine())
                                .latitude(r.getLatitude()).longitude(r.getLongitude())
                                .distanceMiles(distMiles)
                                .build());
                    }
                }
            }

            return RiderSearchResult.builder()
                    .searchLat(lat).searchLng(lng).radiusMiles(radiusMiles)
                    .executionTimeMs(elapsed).count(nearby.size())
                    .restaurants(nearby)
                    .build();

        } catch (Exception e) {
            log.error("Redis geo search failed: {}", e.getMessage());
            return RiderSearchResult.builder()
                    .searchLat(lat).searchLng(lng).radiusMiles(radiusMiles)
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .count(0).restaurants(List.of())
                    .build();
        }
    }

    // ── Full restaurant list (for map initialisation) ─────────────────────────

    /** Returns all restaurants from the in-memory cache — no DB or Redis hit. */
    public List<NearbyRestaurant> getAllRestaurants() {
        return restaurantCache.values().stream()
                .map(r -> NearbyRestaurant.builder()
                        .id(r.getId()).name(r.getName()).cuisine(r.getCuisine())
                        .latitude(r.getLatitude()).longitude(r.getLongitude())
                        .distanceMiles(0.0)
                        .build())
                .toList();
    }
}
